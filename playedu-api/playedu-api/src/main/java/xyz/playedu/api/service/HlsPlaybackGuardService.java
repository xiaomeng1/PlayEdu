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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.playedu.common.util.MemoryCacheUtil;

@Service
@Slf4j
public class HlsPlaybackGuardService {

    private static final long ACTIVE_SESSION_TTL_SECONDS = 45L;
    private static final long PREVIOUS_SESSION_GRACE_MILLIS = 45_000L;
    private static final long SESSION_BOOTSTRAP_GRACE_MILLIS = 60_000L;
    private static final long SEEK_GRACE_MILLIS = 15_000L;
    private static final long SEGMENT_WINDOW_MILLIS = 10_000L;
    private static final int SEGMENT_WINDOW_ALERT_COUNT = 18;
    private static final int SEGMENT_WINDOW_BLOCK_COUNT = 32;
    private static final int SEGMENT_WINDOW_NO_PING_BLOCK_COUNT = 12;
    private static final int SEGMENT_WINDOW_UNIQUE_ALERT_COUNT = 12;
    private static final int MAX_SEGMENT_JUMP_WITHOUT_SEEK = 8;
    private static final long ALERT_DEDUP_SECONDS = 20L;
    private static final Pattern SEGMENT_INDEX_PATTERN =
            Pattern.compile("(?i)(\\d+)(?=\\.(ts|m4s|aac|mp4|vtt)$)");

    public synchronized void onPlayIssued(HlsTokenService.Payload payload) {
        PlaybackSessionState activeState = getActiveState(payload.getUserId());
        long now = System.currentTimeMillis();
        if (activeState != null
                && !activeState.getSessionId().equals(payload.getPlaybackSessionId())) {
            activeState.setGraceUntil(now + PREVIOUS_SESSION_GRACE_MILLIS);
            putSessionState(activeState);
        }

        PlaybackSessionState newState = getSessionState(payload.getUserId(), payload.getPlaybackSessionId());
        if (newState == null) {
            newState =
                    new PlaybackSessionState(
                            payload.getUserId(),
                            payload.getCourseId(),
                            payload.getResourceId(),
                            payload.getPlaybackSessionId(),
                            now,
                            now);
        }
        newState.setIssuedAt(now);
        newState.setLastSeenAt(now);
        newState.setGraceUntil(0L);
        putSessionState(newState);
        putActiveState(newState);
    }

    public synchronized void onPing(HlsTokenService.Payload payload, Integer hourId) {
        PlaybackSessionState activeState = getActiveState(payload.getUserId());
        long now = System.currentTimeMillis();
        if (activeState != null
                && !activeState.getSessionId().equals(payload.getPlaybackSessionId())) {
            activeState.setGraceUntil(now + PREVIOUS_SESSION_GRACE_MILLIS);
            putSessionState(activeState);
        }

        PlaybackSessionState current =
                getOrCreateSessionState(payload.getUserId(), payload.getPlaybackSessionId(), payload, now);
        current.setLastPingAt(now);
        current.setLastSeenAt(now);
        current.setCourseId(payload.getCourseId());
        current.setResourceId(payload.getResourceId());
        current.setHourId(hourId);
        current.setGraceUntil(0L);
        putSessionState(current);
        putActiveState(current);
    }

    public synchronized void onSeek(HlsTokenService.Payload payload, Integer from, Integer to) {
        long now = System.currentTimeMillis();
        PlaybackSessionState current =
                getOrCreateSessionState(payload.getUserId(), payload.getPlaybackSessionId(), payload, now);
        current.setSeekGraceUntil(now + SEEK_GRACE_MILLIS);
        current.setLastSeekFrom(from);
        current.setLastSeekTo(to);
        current.setLastSeenAt(now);
        putSessionState(current);
    }

    public synchronized GuardDecision beforeSegmentAccess(
            HlsTokenService.Payload payload, String segmentName) {
        long now = System.currentTimeMillis();
        PlaybackSessionState current =
                getOrCreateSessionState(payload.getUserId(), payload.getPlaybackSessionId(), payload, now);
        PlaybackSessionState activeState = getActiveState(payload.getUserId());

        if (activeState != null
                && !activeState.getSessionId().equals(payload.getPlaybackSessionId())
                && current.getGraceUntil() < now) {
            emitAlert(
                    "hls-concurrency-block",
                    payload,
                    current,
                    segmentName,
                    "another active playback session is already running");
            return GuardDecision.block("another active playback session exists");
        }

        current.pruneWindow(now);
        current.getRecentSegmentTimes().addLast(now);
        current.getRecentSegmentNames().addLast(segmentName);
        current.setLastSeenAt(now);

        Integer currentSegmentIndex = parseSegmentIndex(segmentName);
        Integer lastSegmentIndex = current.getLastSegmentIndex();
        current.setLastSegmentIndex(currentSegmentIndex);

        if (currentSegmentIndex != null
                && lastSegmentIndex != null
                && Math.abs(currentSegmentIndex - lastSegmentIndex) > MAX_SEGMENT_JUMP_WITHOUT_SEEK
                && current.getSeekGraceUntil() < now) {
            emitAlert(
                    "hls-segment-jump-alert",
                    payload,
                    current,
                    segmentName,
                    String.format("segment jump detected: %d -> %d", lastSegmentIndex, currentSegmentIndex));
        }

        int segmentCount = current.getRecentSegmentTimes().size();
        int uniqueSegmentCount = new HashSet<>(current.getRecentSegmentNames()).size();
        long millisSincePing = now - current.getLastPingAt();

        if (segmentCount >= SEGMENT_WINDOW_ALERT_COUNT || uniqueSegmentCount >= SEGMENT_WINDOW_UNIQUE_ALERT_COUNT) {
            emitAlert(
                    "hls-segment-alert",
                    payload,
                    current,
                    segmentName,
                    String.format(
                            "segment burst detected count=%d unique=%d lastPingMs=%d",
                            segmentCount, uniqueSegmentCount, millisSincePing));
        }

        if (millisSincePing > ACTIVE_SESSION_TTL_SECONDS * 1000
                && current.getIssuedAt() + SESSION_BOOTSTRAP_GRACE_MILLIS < now
                && segmentCount >= SEGMENT_WINDOW_NO_PING_BLOCK_COUNT) {
            emitAlert(
                    "hls-no-ping-block",
                    payload,
                    current,
                    segmentName,
                    String.format("high segment traffic without recent ping count=%d", segmentCount));
            putSessionState(current);
            return GuardDecision.block("segment traffic is abnormal without recent playback heartbeat");
        }

        if (segmentCount >= SEGMENT_WINDOW_BLOCK_COUNT && current.getSeekGraceUntil() < now) {
            emitAlert(
                    "hls-segment-block",
                    payload,
                    current,
                    segmentName,
                    String.format("segment burst exceeds limit count=%d", segmentCount));
            putSessionState(current);
            return GuardDecision.block("segment traffic exceeds playback limit");
        }

        putSessionState(current);
        return GuardDecision.allow();
    }

    private PlaybackSessionState getOrCreateSessionState(
            Integer userId, String playbackSessionId, HlsTokenService.Payload payload, long now) {
        PlaybackSessionState current = getSessionState(userId, playbackSessionId);
        if (current == null) {
            current =
                    new PlaybackSessionState(
                            userId,
                            payload.getCourseId(),
                            payload.getResourceId(),
                            playbackSessionId,
                            now,
                            now);
        }
        return current;
    }

    private PlaybackSessionState getActiveState(Integer userId) {
        return (PlaybackSessionState) MemoryCacheUtil.get(activeStateKey(userId));
    }

    private void putActiveState(PlaybackSessionState state) {
        MemoryCacheUtil.set(activeStateKey(state.getUserId()), state, ACTIVE_SESSION_TTL_SECONDS);
    }

    private PlaybackSessionState getSessionState(Integer userId, String sessionId) {
        return (PlaybackSessionState) MemoryCacheUtil.get(sessionStateKey(userId, sessionId));
    }

    private void putSessionState(PlaybackSessionState state) {
        MemoryCacheUtil.set(sessionStateKey(state.getUserId(), state.getSessionId()), state, ACTIVE_SESSION_TTL_SECONDS);
    }

    private String activeStateKey(Integer userId) {
        return "hls:active:user:" + userId;
    }

    private String sessionStateKey(Integer userId, String sessionId) {
        return "hls:session:user:" + userId + ":" + sessionId;
    }

    private Integer parseSegmentIndex(String segmentName) {
        Matcher matcher = SEGMENT_INDEX_PATTERN.matcher(segmentName);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void emitAlert(
            String alertType,
            HlsTokenService.Payload payload,
            PlaybackSessionState state,
            String segmentName,
            String reason) {
        String dedupKey =
                "hls:alert:"
                        + alertType
                        + ":"
                        + payload.getUserId()
                        + ":"
                        + payload.getPlaybackSessionId();
        if (MemoryCacheUtil.exists(dedupKey)) {
            return;
        }
        MemoryCacheUtil.set(dedupKey, "1", ALERT_DEDUP_SECONDS);
        log.warn(
                "hls playback risk detected, type={}, userId={}, courseId={}, resourceId={}, sessionId={}, hourId={}, segment={}, reason={}",
                alertType,
                payload.getUserId(),
                payload.getCourseId(),
                payload.getResourceId(),
                payload.getPlaybackSessionId(),
                state == null ? null : state.getHourId(),
                segmentName,
                reason);
    }

    public static class GuardDecision {
        private final boolean allowed;
        private final String reason;

        private GuardDecision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static GuardDecision allow() {
            return new GuardDecision(true, "");
        }

        public static GuardDecision block(String reason) {
            return new GuardDecision(false, reason);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }
    }

    public static class PlaybackSessionState {
        private final Integer userId;
        private Integer courseId;
        private Integer resourceId;
        private Integer hourId;
        private final String sessionId;
        private long issuedAt;
        private long lastPingAt;
        private long lastSeenAt;
        private long graceUntil;
        private long seekGraceUntil;
        private Integer lastSeekFrom;
        private Integer lastSeekTo;
        private Integer lastSegmentIndex;
        private final Deque<Long> recentSegmentTimes = new ArrayDeque<>();
        private final Deque<String> recentSegmentNames = new ArrayDeque<>();

        public PlaybackSessionState(
                Integer userId,
                Integer courseId,
                Integer resourceId,
                String sessionId,
                long issuedAt,
                long lastPingAt) {
            this.userId = userId;
            this.courseId = courseId;
            this.resourceId = resourceId;
            this.sessionId = sessionId;
            this.issuedAt = issuedAt;
            this.lastPingAt = lastPingAt;
            this.lastSeenAt = issuedAt;
        }

        public void pruneWindow(long now) {
            while (!recentSegmentTimes.isEmpty()
                    && now - recentSegmentTimes.peekFirst() > SEGMENT_WINDOW_MILLIS) {
                recentSegmentTimes.pollFirst();
                recentSegmentNames.pollFirst();
            }
        }

        public Integer getUserId() {
            return userId;
        }

        public Integer getCourseId() {
            return courseId;
        }

        public void setCourseId(Integer courseId) {
            this.courseId = courseId;
        }

        public Integer getResourceId() {
            return resourceId;
        }

        public void setResourceId(Integer resourceId) {
            this.resourceId = resourceId;
        }

        public Integer getHourId() {
            return hourId;
        }

        public void setHourId(Integer hourId) {
            this.hourId = hourId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getIssuedAt() {
            return issuedAt;
        }

        public void setIssuedAt(long issuedAt) {
            this.issuedAt = issuedAt;
        }

        public long getLastPingAt() {
            return lastPingAt;
        }

        public void setLastPingAt(long lastPingAt) {
            this.lastPingAt = lastPingAt;
        }

        public long getLastSeenAt() {
            return lastSeenAt;
        }

        public void setLastSeenAt(long lastSeenAt) {
            this.lastSeenAt = lastSeenAt;
        }

        public long getGraceUntil() {
            return graceUntil;
        }

        public void setGraceUntil(long graceUntil) {
            this.graceUntil = graceUntil;
        }

        public long getSeekGraceUntil() {
            return seekGraceUntil;
        }

        public void setSeekGraceUntil(long seekGraceUntil) {
            this.seekGraceUntil = seekGraceUntil;
        }

        public Integer getLastSeekFrom() {
            return lastSeekFrom;
        }

        public void setLastSeekFrom(Integer lastSeekFrom) {
            this.lastSeekFrom = lastSeekFrom;
        }

        public Integer getLastSeekTo() {
            return lastSeekTo;
        }

        public void setLastSeekTo(Integer lastSeekTo) {
            this.lastSeekTo = lastSeekTo;
        }

        public Integer getLastSegmentIndex() {
            return lastSegmentIndex;
        }

        public void setLastSegmentIndex(Integer lastSegmentIndex) {
            this.lastSegmentIndex = lastSegmentIndex;
        }

        public Deque<Long> getRecentSegmentTimes() {
            return recentSegmentTimes;
        }

        public Deque<String> getRecentSegmentNames() {
            return recentSegmentNames;
        }
    }
}
