# Unified File Behavior Boundaries

Last updated: 2026-07-28

## Goal

Keep the existing business tables, but route file behavior through the unified `file-assets` contract whenever the action is about a registered file.

Unified file behavior covers:

- preview and download
- open folder and local path
- tags
- default file
- reorder
- move category
- move owner
- soft delete
- restore
- permanent delete with `deletePhysical`

## Unified Owners

The supported owner types are:

- `product_model`
- `product_variant`
- `packaging_model`
- `packaging_variant`
- `company`
- `certificate`
- `document`
- `software`

Each owner is backed by an adapter. The adapter decides which capabilities are allowed for that owner and for each individual `FileHandle`.

## Frontend Boundary

Pages should use these unified helpers first:

- `fileAssetApi(ownerType)`
- `useFileAssets({ ownerType, ownerId })`
- `useFileAssetActions()`
- `fileAssetPreviewUrlWithFallback(...)`
- `openFileAssetFolderWithFallback(...)`
- `useRegisteredFileUpload(...)`

The legacy physical file API `frontend/src/api/file/file.ts` is intentionally limited to three bridge functions:

- `uploadFile`: raw physical upload before a business record is registered
- `listPhysicalFiles`: import page file-browser support
- `openFileFolder`: legacy fallback only after unified open-folder cannot resolve a file

Do not add page-level imports of `api/file/file.ts` for delete, local path, preview, or download. Registered files should use `file-assets`.

## Retained Business Boundaries

These are not pure file behavior and should stay on business APIs unless their workflow is redesigned:

- product model and product variant trash operations
- packaging model and packaging variant trash operations
- registered product asset physical deletion through unified permanent delete with explicit `deletePhysical=true`
- product asset cross-model or cross-variant move when it must preserve catalog-specific semantics
- mixed-owner product or packaging reorder when one request spans multiple owner types
- document status, category, and department batch updates
- software import, installed status, and category business updates
- company account, team, milestone, certification, knowledge-base, and address CRUD
- import-record physical file browsing

## Current Compatibility Rules

- Preview and download should prefer `/api/file-assets/{ownerType}/assets/{assetId}/...`.
- Legacy physical preview URLs are only fallback when there is no unified identity.
- Open folder should prefer unified `ownerType + assetId`, then fallback to legacy `category + fileName + modelId` only for recoverable cases.
- Permanent delete must pass `deletePhysical` explicitly through the unified API.
- Physical delete also removes generated thumbnails through `FileStorageService.deleteIfExists(...)`.
- Legacy database and deployment configuration are out of scope until the main product workflows are stable.
