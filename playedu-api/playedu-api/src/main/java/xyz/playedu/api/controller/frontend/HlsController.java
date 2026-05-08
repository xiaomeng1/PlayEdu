/*
 * Copyright (C) 2023 閺夘厼绐為惂鎴掑姛缁夋垶濡ч張澶愭閸忣剙寰?
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.playedu.api.service.HlsTokenService;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.course.caches.UserCanSeeCourseCache;
import xyz.playedu.resource.service.ResourceService;

@RestController
@RequestMapping({"/api/v1/hls", "/v1/hls"})
public class HlsController {

    private static final long SEGMENT_EXPIRE_SECONDS = 20L;
    private static final Pattern HLS_SEGMENT_LINE =
            Pattern.compile("^(?!#)([^\\r\\n]+)$", Pattern.MULTILINE);

    @Autowired private ResourceService resourceService;

    @Autowired private HlsTokenService hlsTokenService;

    @Autowired private UserCanSeeCourseCache userCanSeeCourseCache;

    @GetMapping("/{resourceId}/index.m3u8")
    @SneakyThrows
    public ResponseEntity<String> getM3u8(
            @PathVariable Integer resourceId, @RequestParam String token) {
        checkToken(resourceId, token);
        String manifest = resourceService.getHlsManifest(resourceId);
        if (manifest == null) {
            throw new ServiceException("video hls manifest is not ready");
        }
        String keyUrl = "/api/v1/hls/" + resourceId + "/key?token=" + token;
        String rewrittenManifest = rewriteManifest(resourceId, token, manifest, keyUrl);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header("Pragma", "no-cache")
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .body(rewrittenManifest);
    }

    @GetMapping("/{resourceId}/key")
    @SneakyThrows
    public ResponseEntity<byte[]> getKey(
            @PathVariable Integer resourceId, @RequestParam String token) {
        checkToken(resourceId, token);
        byte[] key = resourceService.getHlsKey(resourceId);
        if (key == null || key.length == 0) {
            throw new ServiceException("video hls key not found");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(key);
    }

    @GetMapping("/{resourceId}/segment/{segmentName:.+}")
    @SneakyThrows
    public ResponseEntity<Void> getSegment(
            @PathVariable Integer resourceId,
            @PathVariable String segmentName,
            @RequestParam String token) {
        checkToken(resourceId, token);
        String url =
                resourceService.getHlsSegmentPreSignUrl(
                        resourceId, segmentName, SEGMENT_EXPIRE_SECONDS);
        if (url == null) {
            throw new ServiceException("video hls segment not found");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .build();
    }

    private HlsTokenService.Payload checkToken(Integer resourceId, String token) {
        HlsTokenService.Payload payload = hlsTokenService.resolve(token, resourceId);
        if (payload == null
                || !userCanSeeCourseCache.check(payload.getUserId(), payload.getCourseId(), false)) {
            throw new ServiceException("playback token is invalid");
        }
        return payload;
    }

    private String rewriteManifest(
            Integer resourceId, String token, String manifest, String keyUrl) {
        String updatedManifest =
                manifest.replaceAll(
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"[^\"]+\"",
                        Matcher.quoteReplacement(
                                "#EXT-X-KEY:METHOD=AES-128,URI=\"" + keyUrl + "\""));
        Matcher matcher = HLS_SEGMENT_LINE.matcher(updatedManifest);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String segment = matcher.group(1).trim();
            if (segment.isEmpty()
                    || segment.startsWith("http://")
                    || segment.startsWith("https://")) {
                continue;
            }
            String segmentUrl =
                    "/api/v1/hls/"
                            + resourceId
                            + "/segment/"
                            + encodePathSegment(segment)
                            + "?token="
                            + token;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(segmentUrl));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
