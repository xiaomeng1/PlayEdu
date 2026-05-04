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
package xyz.playedu.resource.service.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.AppConfigService;
import xyz.playedu.common.util.S3Util;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.mapper.ResourceMapper;

@Service
@Slf4j
public class HlsTranscodeService {

    private static final int HLS_STATUS_PROCESSING = 1;
    private static final int HLS_STATUS_READY = 2;
    private static final int HLS_STATUS_FAILED = 3;
    private static final String COPY_MODE = "copy";
    private static final String TRANSCODE_MODE = "transcode";
    private static final String HLS_SEGMENT_TIME_SECONDS = "15";
    private static final String X264_PRESET = "veryfast";

    @Autowired private ResourceMapper resourceMapper;

    @Autowired private AppConfigService appConfigService;

    @Value("${playedu.video.ffmpeg:ffmpeg}")
    private String ffmpegCommand;

    @Value("${playedu.video.ffprobe:ffprobe}")
    private String ffprobeCommand;

    @Async
    public void transcode(Integer resourceId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null || !"VIDEO".equals(resource.getType())) {
            return;
        }
        if (resource.getHlsStatus() != null && resource.getHlsStatus() == HLS_STATUS_PROCESSING) {
            log.info("skip hls transcode because task is already processing, resourceId={}", resourceId);
            return;
        }

        Instant taskStart = Instant.now();
        updateStatus(resourceId, HLS_STATUS_PROCESSING, null);
        log.info(
                "start hls transcode, resourceId={}, path={}, extension={}",
                resourceId,
                resource.getPath(),
                resource.getExtension());

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("playedu-hls-");
            Path inputPath = workDir.resolve("input." + resource.getExtension());
            Path keyPath = workDir.resolve("enc.key");
            Path keyInfoPath = workDir.resolve("key_info.txt");
            Path outputDir = workDir.resolve("hls");
            Files.createDirectories(outputDir);

            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String keyHex = HexFormat.of().formatHex(keyBytes);

            S3Util s3Util = new S3Util(appConfigService.getS3Config());

            Instant downloadStart = Instant.now();
            log.info("download source video from s3, resourceId={}, s3Path={}", resourceId, resource.getPath());
            byte[] sourceBytes = s3Util.getBytes(resource.getPath());
            Files.write(inputPath, sourceBytes);
            log.info(
                    "download source video finished, resourceId={}, bytes={}, elapsedMs={}",
                    resourceId,
                    sourceBytes.length,
                    elapsedMillis(downloadStart));

            Files.write(keyPath, keyBytes);
            Files.writeString(
                    keyInfoPath,
                    buildKeyUri(resourceId) + System.lineSeparator() + keyPath.toAbsolutePath(),
                    StandardCharsets.UTF_8);

            String videoCodec = probeCodec(inputPath, "v:0");
            String audioCodec = probeCodec(inputPath, "a:0");
            boolean hasAudio = audioCodec != null && !audioCodec.isBlank();
            String mode = shouldCopy(videoCodec, audioCodec) ? COPY_MODE : TRANSCODE_MODE;
            log.info(
                    "ffprobe finished, resourceId={}, videoCodec={}, audioCodec={}, hasAudio={}, mode={}",
                    resourceId,
                    videoCodec,
                    audioCodec,
                    hasAudio,
                    mode);

            Instant ffmpegStart = Instant.now();
            List<String> command = buildFfmpegCommand(inputPath, keyInfoPath, outputDir, mode, hasAudio);
            log.info("run ffmpeg command, resourceId={}, command={}", resourceId, String.join(" ", command));
            runCommand(command, "FFmpeg转码失败");
            log.info(
                    "ffmpeg finished, resourceId={}, mode={}, elapsedMs={}",
                    resourceId,
                    mode,
                    elapsedMillis(ffmpegStart));

            Instant uploadStart = Instant.now();
            int uploadCount = uploadOutput(resource.getPath(), outputDir, s3Util);
            log.info(
                    "upload hls output finished, resourceId={}, fileCount={}, elapsedMs={}",
                    resourceId,
                    uploadCount,
                    elapsedMillis(uploadStart));

            updateStatus(resourceId, HLS_STATUS_READY, keyHex);
            log.info(
                    "hls transcode finished, resourceId={}, status={}, totalElapsedMs={}",
                    resourceId,
                    HLS_STATUS_READY,
                    elapsedMillis(taskStart));
        } catch (Exception e) {
            log.error(
                    "hls transcode failed, resourceId={}, totalElapsedMs={}",
                    resourceId,
                    elapsedMillis(taskStart),
                    e);
            updateStatus(resourceId, HLS_STATUS_FAILED, null);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private String probeCodec(Path inputPath, String streamSelector)
            throws IOException, InterruptedException {
        List<String> command =
                List.of(
                        ffprobeCommand,
                        "-v",
                        "error",
                        "-select_streams",
                        streamSelector,
                        "-show_entries",
                        "stream=codec_name",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        inputPath.toString());
        String output = runCommand(command, "FFprobe探测失败");
        String codec = output.trim();
        return codec.isBlank() ? null : codec.toLowerCase(Locale.ROOT);
    }

    private boolean shouldCopy(String videoCodec, String audioCodec) {
        boolean videoOk = "h264".equalsIgnoreCase(videoCodec);
        boolean audioOk = audioCodec == null || "aac".equalsIgnoreCase(audioCodec);
        return videoOk && audioOk;
    }

    private List<String> buildFfmpegCommand(
            Path inputPath, Path keyInfoPath, Path outputDir, String mode, boolean hasAudio) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        command.add("-i");
        command.add(inputPath.toString());

        if (COPY_MODE.equals(mode)) {
            command.add("-c:v");
            command.add("copy");
            if (hasAudio) {
                command.add("-c:a");
                command.add("copy");
            } else {
                command.add("-an");
            }
        } else {
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add(X264_PRESET);
            if (hasAudio) {
                command.add("-c:a");
                command.add("aac");
            } else {
                command.add("-an");
            }
        }

        command.add("-hls_time");
        command.add(HLS_SEGMENT_TIME_SECONDS);
        command.add("-hls_key_info_file");
        command.add(keyInfoPath.toString());
        command.add("-hls_playlist_type");
        command.add("vod");
        command.add("-hls_segment_filename");
        command.add(outputDir.resolve("seg_%03d.ts").toString());
        command.add(outputDir.resolve("index.m3u8").toString());
        return command;
    }

    private String runCommand(List<String> command, String errorMessage)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new ServiceException(errorMessage + ": " + output);
        }
        return output;
    }

    private int uploadOutput(String originalPath, Path outputDir, S3Util s3Util) throws IOException {
        String prefix = hlsPrefix(originalPath);
        List<Path> files =
                Files.walk(outputDir).filter(Files::isRegularFile).sorted().toList();
        for (Path file : files) {
            String relative = outputDir.relativize(file).toString().replace("\\", "/");
            String contentType =
                    relative.endsWith(".m3u8")
                            ? "application/vnd.apple.mpegurl"
                            : "video/mp2t";
            log.info(
                    "upload hls file, s3Path={}, contentType={}, bytes={}",
                    prefix + relative,
                    contentType,
                    Files.size(file));
            s3Util.saveBytes(Files.readAllBytes(file), prefix + relative, contentType);
        }
        return files.size();
    }

    private void updateStatus(Integer resourceId, Integer status, String keyHex) {
        Resource resource = new Resource();
        resource.setId(resourceId);
        resource.setHlsStatus(status);
        if (keyHex != null) {
            resource.setHlsKey(keyHex);
        }
        resourceMapper.updateById(resource);
        log.info("update hls status, resourceId={}, status={}", resourceId, status);
    }

    private String buildKeyUri(Integer resourceId) {
        return "https://playedu.invalid/api/v1/hls/" + resourceId + "/key";
    }

    private String hlsPrefix(String originalPath) {
        int dotIndex = originalPath.lastIndexOf('.');
        String basePath = dotIndex > -1 ? originalPath.substring(0, dotIndex) : originalPath;
        return basePath + "_hls/";
    }

    private long elapsedMillis(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private void deleteQuietly(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try {
            Files.walk(workDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException ignored) {
        }
    }
}
