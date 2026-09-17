<template>
  <ListPageLayout class="certifications-view" title="资质合规" description="管理检测报告、营业执照、生产许可证、ISO 认证等时效性合规文件。">
    <CompanyCertificationsTab @preview-asset="openAssetPreview($event as any)" />
  </ListPageLayout>
</template>

<script setup lang="ts">
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import CompanyCertificationsTab from '@/components/company/CompanyCertificationsTab.vue'
import { usePreviewAsset } from '@/composables/ui/useAppBridge'

const openAssetPreview = usePreviewAsset()
</script>
