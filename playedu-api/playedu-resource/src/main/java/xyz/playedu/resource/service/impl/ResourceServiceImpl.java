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

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.playedu.common.constant.BackendConstant;
import xyz.playedu.common.exception.NotFoundException;
import xyz.playedu.common.service.AppConfigService;
import xyz.playedu.common.types.paginate.PaginationResult;
import xyz.playedu.common.types.paginate.ResourcePaginateFilter;
import xyz.playedu.common.util.S3Util;
import xyz.playedu.common.util.StringUtil;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.domain.ResourceCategory;
import xyz.playedu.resource.domain.ResourceExtra;
import xyz.playedu.resource.mapper.ResourceMapper;
import xyz.playedu.resource.service.ResourceCategoryService;
import xyz.playedu.resource.service.ResourceExtraService;
import xyz.playedu.resource.service.ResourceService;

/**
 * @author tengteng
 * @description 閽堝琛ㄣ€恟esource銆戠殑鏁版嵁搴撴搷浣淪ervice瀹炵幇
 * @createDate 2023-02-23 10:50:26
 */
@Service
@Slf4j
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource>
        implements ResourceService {

    private static final Pattern HLS_SEGMENT_LINE =
            Pattern.compile("^(?!#)([^\\r\\n]+)$", Pattern.MULTILINE);

    @Autowired private ResourceExtraService resourceExtraService;

    @Autowired private ResourceCategoryService relationService;

    @Autowired private AppConfigService appConfigService;

    @Autowired private HlsTranscodeService hlsTranscodeService;

    @Override
    public PaginationResult<Resource> paginate(int page, int size, ResourcePaginateFilter filter) {
        PaginationResult<Resource> pageResult = new PaginationResult<>();

        filter.setPageStart((page - 1) * size);
        filter.setPageSize(size);

        pageResult.setData(getBaseMapper().paginate(filter));
        pageResult.setTotal(getBaseMapper().paginateCount(filter));

        return pageResult;
    }

    @Override
    public List<String> paginateType(ResourcePaginateFilter filter) {
        return getBaseMapper().paginateType(filter);
    }

    @Override
    @Transactional
    public Resource create(
            Integer adminId,
            String categoryIds,
            String type,
            String filename,
            String ext,
            Long size,
            String disk,
            String path,
            Integer parentId,
            Integer isHidden) {
        Resource resource = new Resource();
        resource.setAdminId(adminId);
        resource.setType(type);
        resource.setName(filename);
        resource.setExtension(ext);
        resource.setSize(size);
        resource.setDisk(disk);
        resource.setPath(path);
        resource.setCreatedAt(new Date());
        resource.setHlsStatus(0);
        resource.setParentId(parentId);
        resource.setIsHidden(isHidden);
        save(resource);

        if (categoryIds != null && categoryIds.trim().length() > 0) {
            String[] idArray = categoryIds.split(",");
            List<ResourceCategory> relations = new ArrayList<>();
            for (String s : idArray) {
                int tmpId = Integer.parseInt(s);
                if (tmpId == 0) {
                    continue;
                }
                relations.add(
                        new ResourceCategory() {
                            {
                                setCid(tmpId);
                                setRid(resource.getId());
                            }
                        });
            }
            relationService.saveBatch(relations);
        }
        return resource;
    }

    @Override
    @Transactional
    public void update(
            Resource resource,
            Integer adminId,
            String categoryIds,
            String type,
            String filename,
            String ext,
            Long size,
            String disk,
            String path,
            Integer parentId,
            Integer isHidden) {
        resource.setAdminId(adminId);
        resource.setType(type);
        resource.setName(filename);
        resource.setExtension(ext);
        resource.setSize(size);
        resource.setDisk(disk);
        resource.setPath(path);
        resource.setCreatedAt(new Date());
        resource.setParentId(parentId);
        resource.setIsHidden(isHidden);
        updateById(resource);

        if (categoryIds != null && categoryIds.trim().length() > 0) {
            String[] idArray = categoryIds.split(",");
            List<ResourceCategory> relations = new ArrayList<>();
            for (String s : idArray) {
                int tmpId = Integer.parseInt(s);
                if (tmpId == 0) {
                    continue;
                }
                relations.add(
                        new ResourceCategory() {
                            {
                                setCid(tmpId);
                                setRid(resource.getId());
                            }
                        });
            }
            relationService.saveBatch(relations);
        }
    }

    @Override
    public Resource findOrFail(Integer id) throws NotFoundException {
        Resource resource = getById(id);
        if (resource == null) {
            throw new NotFoundException("资源不存在");
        }
        return resource;
    }

    @Override
    public List<Resource> chunks(List<Integer> ids) {
        return list(query().getWrapper().in("id", ids));
    }

    @Override
    public List<Resource> chunks(List<Integer> ids, List<String> fields) {
        return list(query().getWrapper().in("id", ids).select(fields));
    }

    @Override
    public Integer total(String type) {
        return Math.toIntExact(count(query().getWrapper().eq("type", type).eq("is_hidden", 0)));
    }

    @Override
    public Integer duration(Integer id) {
        ResourceExtra resourceExtra =
                resourceExtraService.getOne(
                        resourceExtraService.query().getWrapper().eq("rid", id));
        if (resourceExtra == null) {
            return null;
        }
        return resourceExtra.getDuration();
    }

    @Override
    @Transactional
    public void updateNameAndCategoryId(Integer id, String name, Integer categoryId) {
        Resource resource = new Resource();
        resource.setId(id);
        resource.setName(name);
        updateById(resource);

        relationService.rebuild(
                id,
                new ArrayList<>() {
                    {
                        add(categoryId);
                    }
                });
    }

    @Override
    public List<Integer> categoryIds(Integer resourceId) {
        return relationService
                .list(relationService.query().getWrapper().eq("rid", resourceId))
                .stream()
                .map(ResourceCategory::getCid)
                .toList();
    }

    @Override
    public Integer total(List<String> types) {
        return Math.toIntExact(count(query().getWrapper().in("type", types).eq("is_hidden", 0)));
    }

    @Override
    public Map<Integer, String> chunksPreSignUrlByIds(List<Integer> ids) {
        if (StringUtil.isEmpty(ids)) {
            return new HashMap<>();
        }

        S3Util s3Util = new S3Util(appConfigService.getS3Config());
        Map<Integer, String> preSignUrlMap = new HashMap<>();
        List<Resource> resourceList = list(query().getWrapper().in("id", ids));
        if (StringUtil.isNotEmpty(resourceList)) {
            resourceList.forEach(
                    resource -> {
                        String path = resource.getPath();
                        try {
                            String url = s3Util.generateEndpointPreSignUrl(path, "");
                            if (StringUtil.isNotEmpty(url)) {
                                preSignUrlMap.put(resource.getId(), url);
                            }
                        } catch (Exception e) {
                            log.error("generate pre-sign url failed, resourceId={}", resource.getId(), e);
                        }
                    });
        }
        return preSignUrlMap;
    }

    @Override
    public Map<Integer, String> downloadResById(Integer id) {
        Map<Integer, String> preSignUrlMap = new HashMap<>();
        Resource resource = getById(id);
        if (StringUtil.isNotNull(resource)) {
            String name = resource.getName() + "." + resource.getExtension();
            String url =
                    new S3Util(appConfigService.getS3Config())
                            .generateEndpointPreSignUrl(resource.getPath(), name);
            if (StringUtil.isNotEmpty(url)) {
                preSignUrlMap.put(resource.getId(), url);
            }
        }
        return preSignUrlMap;
    }

    @Override
    public void scheduleHlsTranscode(Integer id) {
        hlsTranscodeService.transcode(id);
    }

    @Override
    public boolean isHlsReady(Resource resource) {
        return resource != null
                && BackendConstant.RESOURCE_TYPE_VIDEO.equals(resource.getType())
                && Integer.valueOf(2).equals(resource.getHlsStatus());
    }

    @Override
    public String getHlsManifest(Integer resourceId, String keyUrl, long expireSeconds) {
        long startAt = System.currentTimeMillis();
        Resource resource = getById(resourceId);
        if (!isHlsReady(resource)) {
            log.warn("hls manifest requested before ready, resourceId={}, hlsStatus={}",
                    resourceId, resource == null ? null : resource.getHlsStatus());
            return null;
        }

        S3Util s3Util = new S3Util(appConfigService.getS3Config());
        String manifest = s3Util.getContent(hlsPlaylistPath(resource.getPath()));
        manifest =
                manifest.replaceAll(
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"[^\"]+\"",
                        Matcher.quoteReplacement("#EXT-X-KEY:METHOD=AES-128,URI=\"" + keyUrl + "\""));

        Matcher matcher = HLS_SEGMENT_LINE.matcher(manifest);
        StringBuffer buffer = new StringBuffer();
        String prefix = hlsPrefix(resource.getPath());
        int segmentCount = 0;
        while (matcher.find()) {
            String segment = matcher.group(1).trim();
            if (segment.isEmpty() || segment.startsWith("http://") || segment.startsWith("https://")) {
                continue;
            }
            String segmentUrl =
                    s3Util.generateEndpointPreSignUrl(prefix + segment, "", expireSeconds);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(segmentUrl));
            segmentCount++;
        }
        matcher.appendTail(buffer);
        log.info(
                "generated hls manifest, resourceId={}, segmentCount={}, elapsedMs={}",
                resourceId,
                segmentCount,
                System.currentTimeMillis() - startAt);
        return buffer.toString();
    }

    @Override
    public byte[] getHlsKey(Integer resourceId) {
        Resource resource = getById(resourceId);
        if (resource == null || StringUtil.isEmpty(resource.getHlsKey())) {
            return null;
        }
        return HexFormat.of().parseHex(resource.getHlsKey());
    }

    private String hlsPrefix(String originalPath) {
        int dotIndex = originalPath.lastIndexOf('.');
        String basePath = dotIndex > -1 ? originalPath.substring(0, dotIndex) : originalPath;
        return basePath + "_hls/";
    }

    private String hlsPlaylistPath(String originalPath) {
        return hlsPrefix(originalPath) + "index.m3u8";
    }
}
