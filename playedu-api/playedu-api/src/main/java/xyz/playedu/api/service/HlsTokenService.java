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
package xyz.playedu.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import xyz.playedu.common.service.UserLoginRecordService;
import xyz.playedu.common.util.IpUtil;
import xyz.playedu.common.util.StringUtil;

@Service
public class HlsTokenService {

    private static final long DEFAULT_EXPIRE_SECONDS = 7200L;

    @Autowired private UserLoginRecordService userLoginRecordService;

    @Value("${sa-token.jwt-secret-key}")
    private String secret;

    public String issue(Integer resourceId, Integer userId, Integer courseId, String jwtJti) {
        if (resourceId == null
                || userId == null
                || courseId == null
                || StringUtil.isBlank(jwtJti)) {
            throw new IllegalArgumentException("invalid hls token context");
        }
        long expiresAt = System.currentTimeMillis() / 1000 + DEFAULT_EXPIRE_SECONDS;
        String payload =
                resourceId
                        + ":"
                        + userId
                        + ":"
                        + courseId
                        + ":"
                        + jwtJti
                        + ":"
                        + UUID.randomUUID()
                        + ":"
                        + currentFingerprint()
                        + ":"
                        + expiresAt;
        String encodedPayload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public boolean verify(String token, Integer resourceId) {
        return resolve(token, resourceId) != null;
    }

    public Payload resolve(String token, Integer resourceId) {
        try {
            if (token == null || token.isBlank() || !token.contains(".")) {
                return null;
            }
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2 || !sign(parts[0]).equals(parts[1])) {
                return null;
            }
            String payload =
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] payloadParts = payload.split(":");
            if (payloadParts.length != 7) {
                return null;
            }
            Payload tokenPayload =
                    new Payload(
                            Integer.parseInt(payloadParts[0]),
                            Integer.parseInt(payloadParts[1]),
                            Integer.parseInt(payloadParts[2]),
                            payloadParts[3],
                            payloadParts[4],
                            payloadParts[5],
                            Long.parseLong(payloadParts[6]));
            if (!tokenPayload.getResourceId().equals(resourceId)
                    || tokenPayload.getExpiresAt() < System.currentTimeMillis() / 1000
                    || !tokenPayload.getFingerprint().equals(currentFingerprint())
                    || !userLoginRecordService.isActive(
                            tokenPayload.getUserId(), tokenPayload.getJwtJti())) {
                return null;
            }
            return tokenPayload;
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hls token sign failed", e);
        }
    }

    private String currentFingerprint() {
        String ip = StringUtil.trim(IpUtil.getIpAddress());
        return sha256Hex(ip);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hls token fingerprint failed", e);
        }
    }

    public static class Payload {
        private final Integer resourceId;
        private final Integer userId;
        private final Integer courseId;
        private final String jwtJti;
        private final String playbackSessionId;
        private final String fingerprint;
        private final Long expiresAt;

        public Payload(
                Integer resourceId,
                Integer userId,
                Integer courseId,
                String jwtJti,
                String playbackSessionId,
                String fingerprint,
                Long expiresAt) {
            this.resourceId = resourceId;
            this.userId = userId;
            this.courseId = courseId;
            this.jwtJti = jwtJti;
            this.playbackSessionId = playbackSessionId;
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }

        public Integer getResourceId() {
            return resourceId;
        }

        public Integer getUserId() {
            return userId;
        }

        public Integer getCourseId() {
            return courseId;
        }

        public String getJwtJti() {
            return jwtJti;
        }

        public String getPlaybackSessionId() {
            return playbackSessionId;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public Long getExpiresAt() {
            return expiresAt;
        }
    }
}
