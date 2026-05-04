/*
 * Copyright (C) 2023 杭州白书科技有限公司
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
package xyz.playedu.common.util;

import com.amazonaws.HttpMethod;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListPartsRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PartListing;
import com.amazonaws.services.s3.model.PartSummary;
import com.amazonaws.services.s3.model.ResponseHeaderOverrides;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.types.config.S3Config;

@Slf4j
public class S3Util {

    private S3Config defaultConfig;
    private volatile AmazonS3 client;
    private volatile boolean bucketChecked;

    public S3Config getS3Config() {
        return defaultConfig;
    }

    public S3Util(S3Config s3Config) {
        defaultConfig = s3Config;
    }

    public S3Util setConfig(S3Config config) {
        defaultConfig = config;
        client = null;
        bucketChecked = false;
        return this;
    }

    @SneakyThrows
    private AmazonS3 getClient() {
        if (defaultConfig == null) {
            throw new ServiceException("存储服务未配置");
        }
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    AWSCredentials credentials =
                            new BasicAWSCredentials(
                                    defaultConfig.getAccessKey(), defaultConfig.getSecretKey());
                    AwsClientBuilder.EndpointConfiguration endpointConfiguration =
                            new AwsClientBuilder.EndpointConfiguration(
                                    defaultConfig.getEndpoint(), defaultConfig.getRegion());
                    client =
                            AmazonS3ClientBuilder.standard()
                                    .withCredentials(new AWSStaticCredentialsProvider(credentials))
                                    .withEndpointConfiguration(endpointConfiguration)
                                    .build();
                }
            }
        }
        ensureBucketReady(client);
        return client;
    }

    private void ensureBucketReady(AmazonS3 currentClient) {
        if (bucketChecked) {
            return;
        }
        synchronized (this) {
            if (bucketChecked) {
                return;
            }
            String bucket = defaultConfig.getBucket();
            if (!currentClient.doesBucketExistV2(bucket)) {
                throw new ServiceException("Bucket " + bucket + " 不存在");
            }

            AccessControlList acl = currentClient.getBucketAcl(bucket);
            boolean isPrivate = true;
            for (Grant grant : acl.getGrantsAsList()) {
                if (grant.getGrantee() instanceof GroupGrantee
                        && (GroupGrantee.AllUsers.equals(grant.getGrantee())
                                || GroupGrantee.AuthenticatedUsers.equals(grant.getGrantee()))) {
                    isPrivate = false;
                    break;
                }
            }
            if (!isPrivate) {
                throw new ServiceException("Bucket " + bucket + " 必须设置为私有访问权限");
            }
            bucketChecked = true;
        }
    }

    @SneakyThrows
    public String saveFile(MultipartFile file, String savePath, String contentType) {
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentType(contentType);
        objectMetadata.setContentLength(file.getInputStream().available());
        getClient()
                .putObject(
                        defaultConfig.getBucket(), savePath, file.getInputStream(), objectMetadata);
        return generateEndpointPreSignUrl(savePath);
    }

    @SneakyThrows
    public String saveBytes(byte[] file, String savePath, String contentType) {
        InputStream inputStream = new ByteArrayInputStream(file);
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentType(contentType);
        objectMetadata.setContentLength(inputStream.available());
        getClient().putObject(defaultConfig.getBucket(), savePath, inputStream, objectMetadata);
        return generateEndpointPreSignUrl(savePath);
    }

    public String uploadId(String path) {
        InitiateMultipartUploadRequest request =
                new InitiateMultipartUploadRequest(defaultConfig.getBucket(), path);
        InitiateMultipartUploadResult result = getClient().initiateMultipartUpload(request);
        return result.getUploadId();
    }

    @SneakyThrows
    public UploadPartResult uploadPart(
            byte[] file, String filename, String uploadId, int partNumber) {
        InputStream inputStream = new ByteArrayInputStream(file);
        UploadPartRequest uploadPartRequest =
                new UploadPartRequest()
                        .withBucketName(defaultConfig.getBucket())
                        .withKey(filename)
                        .withUploadId(uploadId)
                        .withPartNumber(partNumber)
                        .withInputStream(inputStream)
                        .withPartSize(file.length);
        return getClient().uploadPart(uploadPartRequest);
    }

    public List<PartSummary> listParts(String uploadId, String filename) {
        ListPartsRequest request =
                new ListPartsRequest(defaultConfig.getBucket(), filename, uploadId);
        PartListing partListing = getClient().listParts(request);
        return partListing.getParts();
    }

    public void purgeSegments(String uploadId, String filename) {
        AbortMultipartUploadRequest request =
                new AbortMultipartUploadRequest(defaultConfig.getBucket(), filename, uploadId);
        getClient().abortMultipartUpload(request);
    }

    public String generatePartUploadPreSignUrl(
            String filename, String partNumber, String uploadId) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(
                        defaultConfig.getBucket(), filename, HttpMethod.PUT);
        request.setExpiration(new Date(System.currentTimeMillis() + 3600 * 1000));
        request.addRequestParameter("partNumber", partNumber);
        request.addRequestParameter("uploadId", uploadId);
        return getClient().generatePresignedUrl(request).toString();
    }

    @SneakyThrows
    public String merge(String filename, String uploadId) {
        AmazonS3 currentClient = getClient();
        ListPartsRequest listPartsRequest =
                new ListPartsRequest(defaultConfig.getBucket(), filename, uploadId);
        PartListing parts = currentClient.listParts(listPartsRequest);
        if (parts.getParts().isEmpty()) {
            throw new ServiceException("没有已上传的分片文件");
        }

        List<PartETag> eTags = new ArrayList<>();
        parts.getParts()
                .forEach(item -> eTags.add(new PartETag(item.getPartNumber(), item.getETag())));

        CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest();
        request.setBucketName(defaultConfig.getBucket());
        request.setKey(filename);
        request.setUploadId(uploadId);
        request.setPartETags(eTags);
        currentClient.completeMultipartUpload(request);
        return generateEndpointPreSignUrl(filename);
    }

    public void removeByPath(String path) {
        DeleteObjectRequest request = new DeleteObjectRequest(defaultConfig.getBucket(), path);
        getClient().deleteObject(request);
    }

    public boolean exists(String path) {
        return getClient().doesObjectExist(defaultConfig.getBucket(), path);
    }

    @SneakyThrows
    public String getContent(String path) {
        S3Object s3Object = getClient().getObject(defaultConfig.getBucket(), path);
        return new String(s3Object.getObjectContent().readAllBytes(), StandardCharsets.UTF_8);
    }

    public String generateEndpointPreSignUrl(String path) {
        return generateEndpointPreSignUrl(path, "", 3600L * 3);
    }

    public String generateEndpointPreSignUrl(String path, String name) {
        return generateEndpointPreSignUrl(path, name, 3600L * 3);
    }

    public String generateEndpointPreSignUrl(String path, String name, long expireSeconds) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(defaultConfig.getBucket(), path, HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + expireSeconds * 1000));
        if (StringUtil.isNotEmpty(name)) {
            ResponseHeaderOverrides responseHeaders = new ResponseHeaderOverrides();
            responseHeaders.setContentDisposition("attachment; filename=\"" + name + "\"");
            request.setResponseHeaders(responseHeaders);
        }
        return getClient().generatePresignedUrl(request).toString();
    }

    @SneakyThrows
    public byte[] getBytes(String path) {
        S3Object s3Object = getClient().getObject(defaultConfig.getBucket(), path);
        return s3Object.getObjectContent().readAllBytes();
    }
}
