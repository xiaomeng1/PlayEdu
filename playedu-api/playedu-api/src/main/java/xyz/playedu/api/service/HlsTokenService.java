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
package xyz.playedu.api.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HlsTokenService {

    private static final long DEFAULT_EXPIRE_SECONDS = 7200L;

    @Value("${sa-token.jwt-secret-key}")
    private String secret;

    public String issue(Integer resourceId, Integer userId) {
        long expiresAt = System.currentTimeMillis() / 1000 + DEFAULT_EXPIRE_SECONDS;
        String payload = resourceId + ":" + userId + ":" + expiresAt;
        String encodedPayload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public boolean verify(String token, Integer resourceId) {
        try {
            if (token == null || token.isBlank() || !token.contains(".")) {
                return false;
            }
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2 || !sign(parts[0]).equals(parts[1])) {
                return false;
            }
            String payload =
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] payloadParts = payload.split(":");
            if (payloadParts.length != 3) {
                return false;
            }
            long expiresAt = Long.parseLong(payloadParts[2]);
            return Integer.parseInt(payloadParts[0]) == resourceId
                    && expiresAt >= System.currentTimeMillis() / 1000;
        } catch (Exception e) {
            return false;
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
}
