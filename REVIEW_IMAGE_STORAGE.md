# TeaSmart Review Images — local setup and operations

## Configuration

Set `REVIEW_IMAGE_STORAGE_ROOT` to an absolute private directory outside every Git checkout.
Development fallback: `${user.home}/.teasmart/review-images` (Windows user profile).
Do not configure static resources for this directory. Restrict filesystem ACLs to the backend account.
The backend checks absolute paths, Git ancestors, symbolic links and resolved directory paths
(including redirected ancestors). UUID keys are generated internally; client filenames are discarded.
The root contains `staging/`, `files/` and `cleanup/` only.
For cloud deployment, replace `ReviewImageStorage`; use persistent object storage instead of ephemeral disk.
Credentials belong in the deployment environment, never in this repository.

## Migration

`database/migrations/0511_create_review_images.sql` is a manual, one-time migration.
It has already been applied on the local teasmart database during 05.11-D2.
Back up the database and exclude concurrent writers before applying on another environment.
Do not rerun it on a database that already contains review_images.
Hibernate remains `ddl-auto=validate`; Spring does not execute the migration automatically.

## Validation and normalization

1–5 files per multipart request (`files`); at most five stored images per review.
Each input and normalized image: at most 5 MiB; dimensions at most 20 million pixels.
Multipart request cap: 26 MiB (allows five 5 MiB files plus multipart overhead).
Signature and actual ImageIO decoding must agree. Width/height are checked before full decode.
Only two validation batches decode concurrently; excess requests receive a retryable 503.
JPEG is re-encoded as JPEG; PNG and static WebP are re-encoded as PNG using a fresh raster.
Uploaded metadata is not retained. Animated WebP is rejected. Original filenames and MIME headers
are not trusted. EXIF rotation and color-profile preservation are not implemented in this slice;
frontend should preview the normalized result rather than assume original metadata is preserved.

WebP decoder: TwelveMonkeys `imageio-webp:3.15.2`, verified on Java 17 with both lossy and lossless
WebP fixtures, as well as JPEG and PNG.
Sources: https://github.com/haraldk/TwelveMonkeys and
https://central.sonatype.com/artifact/com.twelvemonkeys.imageio/imageio-webp/3.15.2

## API and access

- POST `/api/reviews/{reviewId}/images`: Customer owner, multipart `files`, 201 array.
- DELETE `/api/reviews/{reviewId}/images/{imageId}`: Customer owner, 204.
- GET `/api/review-images/{imageId}`: binary, no-store, nosniff.

APPROVED images are public; PENDING/HIDDEN images require owner or Admin; DELETED images require
Admin. Unauthorized image reads return 404. Customer mutations of DELETED reviews return 409.
Image mutations change APPROVED to PENDING, preserve PENDING/HIDDEN and update updatedAt.
Review responses include `images` arrays with IDs, API URLs, normalized content type, byte size
and createdAt. The storage key and filesystem paths are never returned.
For private browser images, React should fetch with its Bearer header and display a blob URL;
ordinary `<img src>` requests do not attach a Bearer header. Revoke blob URLs after use.

## Transactions and cleanup

Validate/decode and stage before starting the upload transaction. Acquire locks in the order
User → Review → ReviewImage. Count using a locking current-read, then finalize files and save
metadata. Pages fetch all image metadata in one batch query.
Confirmed transaction rollback removes all staged/finalized files from that batch.
Unknown commit outcome preserves files: inspect persisted metadata before deciding whether to delete.
Delete metadata transactionally; physical deletion happens after commit. Failed confirmed deletions
write durable markers under cleanup/ and retry every 60 seconds, including after restart.
If storage is inaccessible even for writing the cleanup marker, an operator warning is logged.

Database and filesystem do not share a distributed transaction. A process crash can leave orphan
staging or final files (or a file pending deletion after metadata commit). To reconcile: pause uploads,
back up metadata and storage, compare UUID keys in files/ and staging/ with review_images.storage_key,
and delete only confirmed unreferenced files. Never run blind age-only cleanup while uploads are active.
Do not remove files whose transaction outcome has not been resolved. Restore metadata and storage
together when recovering a backup.

## Verification scope

D2 smoke: valid JPEG, PNG, lossy/lossless WebP uploads; Customer/Admin/private/public reads;
images in Customer/Public/Admin DTOs; APPROVED→PENDING and HIDDEN preservation; soft-deleted access;
limit rejection with rollback cleanup; invalid signature/empty file/invalid ID; binary headers.
Fixture data and files are removed afterward, without resetting AUTO_INCREMENT.
Full concurrency, injected commit uncertainty/storage failures, compressed-image attacks,
large multipart boundaries and crash recovery belong to 05.11-D3; they are not claimed as tested here.

## D2 file inventory

New files (13):

- `REVIEW_IMAGE_STORAGE.md`
- `database/migrations/0511_create_review_images.sql`
- `src/main/java/vn/teasmart/backend/config/ReviewImageCleanupConfig.java`
- `src/main/java/vn/teasmart/backend/controller/ReviewImageController.java`
- `src/main/java/vn/teasmart/backend/dto/response/ReviewImageResponse.java`
- `src/main/java/vn/teasmart/backend/entity/ReviewImage.java`
- `src/main/java/vn/teasmart/backend/exception/ReviewImageException.java`
- `src/main/java/vn/teasmart/backend/repository/ReviewImageRepository.java`
- `src/main/java/vn/teasmart/backend/service/ReviewImageMapper.java`
- `src/main/java/vn/teasmart/backend/service/ReviewImageService.java`
- `src/main/java/vn/teasmart/backend/storage/LocalReviewImageStorage.java`
- `src/main/java/vn/teasmart/backend/storage/ReviewImageStorage.java`
- `src/main/java/vn/teasmart/backend/storage/ReviewImageValidator.java`

Modified files (10):

- `database/schema.sql`
- `pom.xml`
- `src/main/java/vn/teasmart/backend/config/SecurityConfig.java`
- `src/main/java/vn/teasmart/backend/dto/response/AdminReviewResponse.java`
- `src/main/java/vn/teasmart/backend/dto/response/PublicReviewResponse.java`
- `src/main/java/vn/teasmart/backend/dto/response/ReviewResponse.java`
- `src/main/java/vn/teasmart/backend/exception/GlobalExceptionHandler.java`
- `src/main/java/vn/teasmart/backend/service/AdminReviewService.java`
- `src/main/java/vn/teasmart/backend/service/ReviewService.java`
- `src/main/resources/application.properties`

Final checks: clean compile 141 source files PASS; startup/Hibernate validation PASS;
64 HTTP requests and 92 assertions PASS across the initial smoke and the final-source recheck.
All 18 table snapshots match after fixture cleanup (AUTO_INCREMENT was not reset).
Server stopped, port 8080 free. Git staging is empty; no commit or push was performed.
