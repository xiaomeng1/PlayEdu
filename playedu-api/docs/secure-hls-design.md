# Secure HLS Design

## Background

The current HLS flow protects `index.m3u8` and `enc.key`, but it rewrites every segment line in the playlist to a direct S3 pre-signed URL.

That has three problems:

1. Segment requests no longer pass through backend authorization.
2. Once the playlist is leaked, every segment can be fetched directly until the S3 URL expires.
3. The current HLS token payload contains `userId`, but verification only checks `resourceId` and `exp`, so the token is not actually bound to a user session.

## Goals

1. Keep `m3u8`, `key`, and `ts` under backend control.
2. Bind playback authorization to the requesting user and login session.
3. Reduce the value of leaked playlist URLs.
4. Avoid introducing new infrastructure dependencies for the first implementation.
5. Keep S3 private and continue using short-lived pre-signed URLs behind backend authorization.

## Non-goals

1. DRM-grade anti-download protection.
2. Perfect prevention of screen recording or advanced packet capture.
3. Cross-instance shared playback session state in this iteration.

## High-level Design

### Playback entry

`GET /api/v1/course/{courseId}/hour/{id}/play`

After the existing course permission check succeeds, the backend issues an HLS playback token and returns:

`/api/v1/hls/{resourceId}/index.m3u8?token=...`

### HLS endpoints

The backend keeps all HLS traffic on backend routes:

1. `GET /api/v1/hls/{resourceId}/index.m3u8?token=...`
2. `GET /api/v1/hls/{resourceId}/key?token=...`
3. `GET /api/v1/hls/{resourceId}/segment/{segmentName}?token=...`

### Segment delivery

Segment requests are authorized by the backend first.

If authorization succeeds, the backend generates a very short-lived S3 pre-signed URL for that single segment and responds with `302 Found`.

This keeps application bandwidth low while restoring backend control over every segment request.

## Token Design

The HLS token is an HMAC-signed opaque token. Its payload contains:

1. `resourceId`
2. `userId`
3. `courseId`
4. `jwtJti`
5. `fingerprint`
6. `expiresAt`

Format:

`base64url(resourceId:userId:courseId:jwtJti:fingerprint:expiresAt).signature`

### Fingerprint

The token is bound to a lightweight request fingerprint generated when `/play` is called:

`sha256(normalizedIp + "|" + normalizedUserAgent)`

The same fingerprint must be present when fetching `m3u8`, `key`, and `segment`.

This is not absolute protection, but it raises the cost of forwarding a copied HLS URL to another device.

### Expiration

Use a shorter HLS token TTL than the current implementation.

Recommended initial value:

- HLS token: `10 minutes`
- Segment S3 redirect URL: `20 seconds`

## Manifest Rewrite

The backend no longer rewrites segment lines to S3 URLs.

Instead, it rewrites:

1. `#EXT-X-KEY` to `/api/v1/hls/{resourceId}/key?token=...`
2. Every relative segment line to `/api/v1/hls/{resourceId}/segment/{segmentName}?token=...`

Only relative segment lines are rewritten. Absolute URLs are preserved as-is.

## Authorization Rules

All HLS routes must validate the HLS token and reject requests if any of these checks fail:

1. Signature invalid
2. Token expired
3. `resourceId` mismatch
4. `userId` missing or invalid
5. `courseId` missing or invalid
6. `jwtJti` missing
7. `fingerprint` mismatch
8. User no longer has course access

For course access, reuse the existing `UserCanSeeCourseCache`.

## API and Service Changes

### `HlsTokenService`

Change from simple `verify(token, resourceId)` to parsing and validating a structured token.

New responsibilities:

1. Issue token with `resourceId/userId/courseId/jwtJti/fingerprint/expiresAt`
2. Parse payload safely
3. Verify signature
4. Verify expiry
5. Verify route `resourceId`
6. Verify current request fingerprint

### `ResourceService`

Split current responsibilities:

1. Return the raw HLS manifest from S3
2. Return the AES key
3. Generate a short-lived pre-signed URL for a specific HLS segment
4. Resolve and validate HLS segment paths

The service should no longer build backend playlist URLs implicitly from inside the resource layer.

### `HlsController`

Add a new segment endpoint and move playlist rewrite orchestration into the controller layer.

## Security Tradeoffs

### Why not direct backend streaming of segment bytes

Streaming bytes through the application is safer, but it moves all segment traffic onto the API service.

For this iteration, `302 -> short-lived single-segment pre-signed URL` is a better cost/performance tradeoff.

### Why not rely on frontend auth headers

Many HLS players and browser-native media pipelines do not reliably send application auth headers for every playlist and segment request.

The HLS token therefore needs to be self-contained.

### Why not use server-side playback sessions in Redis now

The repository currently does not include a shared Redis session mechanism for this flow.

The first implementation should avoid adding infra requirements. If stronger revocation or cross-instance consistency is later required, add a shared playback-session store.

## Future Enhancements

1. Redis-backed playback session registry
2. Single-account concurrency limits
3. Token rotation during long playback sessions
4. Optional Nginx internal redirect or CDN signed origin flow
5. Support for variant playlists and nested HLS manifests if needed

## Implementation Checklist

1. Replace segment S3 URL rewriting with backend segment URLs.
2. Add `segment` endpoint.
3. Bind HLS token to `userId`, `courseId`, `jwtJti`, and request fingerprint.
4. Shorten HLS token TTL.
5. Shorten per-segment S3 pre-signed URL TTL.
6. Re-check course authorization for each HLS request.
7. Set `Cache-Control: no-store` for playlist and segment redirect responses.
8. Keep `key` response private and short-cache only.
