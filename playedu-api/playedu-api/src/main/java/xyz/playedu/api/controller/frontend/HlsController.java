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

import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xyz.playedu.api.service.HlsTokenService;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.resource.service.ResourceService;

@RestController
@RequestMapping({"/api/v1/hls", "/v1/hls"})
public class HlsController {

    private static final long SEGMENT_EXPIRE_SECONDS = 7200L;

    @Autowired private ResourceService resourceService;

    @Autowired private HlsTokenService hlsTokenService;

    @GetMapping("/{resourceId}/index.m3u8")
    @SneakyThrows
    public ResponseEntity<String> getM3u8(
            @PathVariable Integer resourceId, @RequestParam String token) {
        checkToken(resourceId, token);
        String keyUrl = "/api/v1/hls/" + resourceId + "/key?token=" + token;
        String manifest = resourceService.getHlsManifest(resourceId, keyUrl, SEGMENT_EXPIRE_SECONDS);
        if (manifest == null) {
            throw new ServiceException("视频转码尚未完成");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .body(manifest);
    }

    @GetMapping("/{resourceId}/key")
    @SneakyThrows
    public ResponseEntity<byte[]> getKey(
            @PathVariable Integer resourceId, @RequestParam String token) {
        checkToken(resourceId, token);
        byte[] key = resourceService.getHlsKey(resourceId);
        if (key == null || key.length == 0) {
            throw new ServiceException("视频密钥不存在");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(key);
    }

    private void checkToken(Integer resourceId, String token) {
        if (!hlsTokenService.verify(token, resourceId)) {
            throw new ServiceException("播放地址已失效");
        }
    }
}
