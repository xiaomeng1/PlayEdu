/*
 * Copyright (C) 2023 鏉窞鐧戒功绉戞妧鏈夐檺鍏徃
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.playedu.api.controller.frontend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import xyz.playedu.api.event.UserCourseHourFinishedEvent;
import xyz.playedu.api.event.UserLearnCourseUpdateEvent;
import xyz.playedu.api.request.frontend.CourseHourRecordRequest;
import xyz.playedu.api.service.HlsTokenService;
import xyz.playedu.common.context.FCtx;
import xyz.playedu.common.types.JsonResponse;
import xyz.playedu.common.util.MemoryDistributedLock;
import xyz.playedu.course.caches.UserCanSeeCourseCache;
import xyz.playedu.course.caches.UserLastLearnTimeCache;
import xyz.playedu.course.domain.Course;
import xyz.playedu.course.domain.CourseHour;
import xyz.playedu.course.domain.UserCourseHourRecord;
import xyz.playedu.course.service.CourseHourService;
import xyz.playedu.course.service.CourseService;
import xyz.playedu.course.service.UserCourseHourRecordService;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.service.ResourceService;

@RestController
@RequestMapping("/api/v1/course/{courseId}/hour")
public class HourController {

    @Autowired private CourseService courseService;

    @Autowired private CourseHourService hourService;

    @Autowired private ResourceService resourceService;

    @Autowired private UserCourseHourRecordService userCourseHourRecordService;

    @Autowired private UserCanSeeCourseCache userCanSeeCourseCache;

    @Autowired private MemoryDistributedLock distributedLock;

    @Autowired private UserLastLearnTimeCache userLastLearnTimeCache;

    @Autowired private ApplicationContext ctx;

    @Autowired private HlsTokenService hlsTokenService;

    @GetMapping("/{id}")
    @SneakyThrows
    public JsonResponse detail(
            @PathVariable(name = "courseId") Integer courseId,
            @PathVariable(name = "id") Integer id) {
        Course course = courseService.findOrFail(courseId);
        CourseHour courseHour = hourService.findOrFail(id, courseId);

        UserCourseHourRecord userCourseHourRecord = null;
        if (FCtx.getId() != null && FCtx.getId() > 0) {
            userCourseHourRecord = userCourseHourRecordService.find(FCtx.getId(), courseId, id);
        }

        HashMap<String, Object> data = new HashMap<>();
        data.put("course", course);
        data.put("hour", courseHour);
        data.put("user_hour_record", userCourseHourRecord);

        return JsonResponse.data(data);
    }

    @GetMapping("/{id}/play")
    @SneakyThrows
    public JsonResponse play(
            @PathVariable(name = "courseId") Integer courseId,
            @PathVariable(name = "id") Integer id) {
        userCanSeeCourseCache.check(FCtx.getId(), courseId, true);
        CourseHour hour = hourService.findOrFail(id, courseId);
        Resource resource = resourceService.findOrFail(hour.getRid());

        HashMap<String, Object> data = new HashMap<>();
        if (resourceService.isHlsReady(resource)) {
            String token =
                    hlsTokenService.issue(
                            resource.getId(), FCtx.getId(), courseId, FCtx.getJwtJti());
            data.put(
                    "resource_url",
                    new HashMap<Integer, String>() {
                        {
                            put(
                                    resource.getId(),
                                    "/api/v1/hls/"
                                            + resource.getId()
                                            + "/index.m3u8?token="
                                            + token);
                        }
                    });
        } else {
            data.put(
                    "resource_url",
                    resourceService.chunksPreSignUrlByIds(
                            new ArrayList<>() {
                                {
                                    add(resource.getId());
                                }
                            }));
        }
        data.put("extension", resource.getExtension());
        data.put("duration", resourceService.duration(resource.getId()));
        data.put("hls_status", resource.getHlsStatus());

        return JsonResponse.data(data);
    }

    @PostMapping("/{id}/record")
    @SneakyThrows
    public JsonResponse record(
            @PathVariable(name = "courseId") Integer courseId,
            @PathVariable(name = "id") Integer id,
            @RequestBody @Validated CourseHourRecordRequest req) {
        Integer duration = req.getDuration();
        if (duration <= 0) {
            return JsonResponse.error("duration参数错误");
        }

        CourseHour hour = hourService.findOrFail(id, courseId);
        userCanSeeCourseCache.check(FCtx.getId(), courseId, true);

        String lockKey = String.format("record:%d", FCtx.getId());
        boolean tryLock = distributedLock.tryLock(lockKey, 5, TimeUnit.SECONDS);
        if (!tryLock) {
            return JsonResponse.success();
        }

        try {
            boolean isFinished =
                    userCourseHourRecordService.storeOrUpdate(
                            FCtx.getId(), courseId, hour.getId(), duration, hour.getDuration());
            if (isFinished) {
                ctx.publishEvent(
                        new UserCourseHourFinishedEvent(
                                this, FCtx.getId(), courseId, hour.getId()));
            }
        } finally {
            distributedLock.releaseLock(lockKey);
        }

        return JsonResponse.success();
    }

    @PostMapping("/{id}/ping")
    @SneakyThrows
    public JsonResponse ping(
            @PathVariable(name = "courseId") Integer courseId,
            @PathVariable(name = "id") Integer id) {
        userCanSeeCourseCache.check(FCtx.getId(), courseId, true);

        String lockKey = String.format("ping:%d", FCtx.getId());
        boolean tryLock = distributedLock.tryLock(lockKey, 5, TimeUnit.SECONDS);
        if (!tryLock) {
            return JsonResponse.success();
        }

        try {
            Long curTime = System.currentTimeMillis();
            Long lastTime = userLastLearnTimeCache.get(FCtx.getId());
            if (lastTime == null || curTime - lastTime > 10500) {
                lastTime = curTime - 10000;
            }

            userLastLearnTimeCache.put(FCtx.getId(), curTime);

            ctx.publishEvent(
                    new UserLearnCourseUpdateEvent(
                            this, FCtx.getId(), courseId, id, lastTime, curTime));
        } finally {
            distributedLock.releaseLock(lockKey);
        }

        return JsonResponse.success();
    }
}
