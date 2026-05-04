# PlayEdu HLS 加密视频方案

> 目标：提高普通用户下载门槛，防止直链下载和未授权访问，不承诺绝对防下载。

---

## 一、方案结论

这个方案适合 PlayEdu 这种课程点播场景，可以做到：

- 页面上不暴露 MP4 直链
- 视频分片是 AES-128 加密的 `.ts`
- 密钥不落 S3，只由后端按 token 下发
- `.ts` 分片使用 S3 签名 URL，默认 **2 小时有效**
- 未登录或无权限用户拿不到可播放地址

要明确一个边界：

- 该方案是“防普通下载”，不是 DRM
- 只要用户能播放，理论上就能抓到 `m3u8 + ts + key`
- 所以它是“足够提高门槛”，不是“绝对不可下载”

---

## 二、整体流程

### 上传阶段

1. Admin 上传 MP4 到私有 S3 桶
2. 后端创建 `resource` 记录
3. 如果是视频资源，异步触发 `FFmpeg` 转码
4. 生成 16 字节 AES-128 密钥
5. 转成 HLS，切成 10 秒一个的 `.ts`
6. 生成 `index.m3u8`
7. 上传 `index.m3u8 + seg_xxx.ts` 到私有 S3
8. 把密钥 hex 存到 `resource.hls_key`
9. 更新 `resource.hls_status`

### 播放阶段

1. 学员调用 `/api/v1/course/{courseId}/hour/{id}/play`
2. 后端先校验课程观看权限
3. 如果视频已转码完成，后端生成 **2 小时有效** 的 HLS token
4. 返回 `/api/v1/hls/{resourceId}/index.m3u8?token=xxx`
5. 播放器加载 m3u8
6. 后端校验 token，重写：
   - `EXT-X-KEY` 为 `/api/v1/hls/{resourceId}/key?token=xxx`
   - `.ts` 相对路径为 **2 小时有效** 的 S3 签名 URL
7. 播放器请求密钥并解密分片播放

---

## 三、为什么这样能防普通下载

| 用户行为 | 结果 |
|---|---|
| 右键另存为 | 页面没有 MP4 直链 |
| 看开发者工具 | 只能看到 m3u8、加密分片和短期 token |
| 直接下 `.ts` | 是 AES-128 加密分片，不能直接播放 |
| 访问 `enc.key` | 密钥不存 S3 |
| 复制老分片 URL | 2 小时后失效 |

---

## 四、后端实现要点

### 4.1 数据库字段

```sql
ALTER TABLE resource
  ADD COLUMN hls_status TINYINT NOT NULL DEFAULT 0
    COMMENT '0:未转码 1:转码中 2:转码完成 3:转码失败',
  ADD COLUMN hls_key VARCHAR(64) NULL
    COMMENT 'AES-128密钥，hex字符串';
```

### 4.2 核心文件

```text
playedu-resource/
  └── service/impl/
        ├── HlsTranscodeService.java
        └── ResourceServiceImpl.java

playedu-api/
  └── controller/frontend/
        └── HlsController.java

playedu-api/
  └── service/
        └── HlsTokenService.java
```

### 4.3 转码逻辑

- 仅视频资源触发
- 使用 `@Async`
- `ffmpeg` 命令默认取系统 PATH 中的 `ffmpeg`
- 输出目录规则：
  - 原视频：`videos/abc.mp4`
  - HLS 输出：`videos/abc_hls/index.m3u8`
  - 分片：`videos/abc_hls/seg_000.ts`

### 4.4 播放 token

当前实现用的是**签名 token**，不是内存缓存：

- token 内包含 `resourceId + userId + expiresAt`
- 使用服务端密钥做 HMAC-SHA256 签名
- 默认有效期：**2 小时**

这样做的好处是：

- 单机和多实例都能用
- 不依赖本地内存缓存

---

## 五、播放器接入

前端仍然使用 `DPlayer`，但要让它识别 HLS：

```ts
video: {
  url: playUrl,
  pic: systemConfig.playerPoster,
  type: playUrl.includes(".m3u8") ? "hls" : "auto",
}
```

同时页面需要先加载 `hls.js`，让 DPlayer 在桌面浏览器中能播 m3u8。

---

## 六、签名 URL 为什么改成 2 小时

5 分钟太短，真实用户会遇到这些情况：

- 视频较长
- 中途暂停
- 拖动进度条
- 网络抖动

所以分片签名改成 **2 小时** 更稳，能覆盖绝大多数课程播放会话。

---

## 七、实施检查项

- [ ] 服务器已安装 `ffmpeg`
- [ ] `resource` 表已新增 `hls_status`、`hls_key`
- [ ] 视频上传后能自动触发 HLS 转码
- [ ] `resource.hls_status = 2` 后前台返回 m3u8 地址
- [ ] `/api/v1/hls/{resourceId}/index.m3u8` 可返回合法 playlist
- [ ] `/api/v1/hls/{resourceId}/key` 返回 16 字节二进制
- [ ] `.ts` 分片 URL 为 2 小时签名地址
- [ ] PC/H5 播放正常

---

## 八、已知边界

- 不是 DRM
- 不能防录屏
- 不能防高级用户抓包还原
- 目标是“普通用户难下载”，不是“任何人都不可能下载”
