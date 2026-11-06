import { expect, test } from '@playwright/test'

test('manual sync of tenant-a succeeds from tenant-a-stations', async ({ page }) => {
  await page.goto('/tenants/tenant-a/health')
  await page.getByRole('button', { name: './sync →' }).click()

  await expect(page.getByRole('group', { name: 'Last manual sync' }))
    .toContainText('SUCCESS via tenant-a-stations', { timeout: 30_000 })
  await expect(page.getByRole('group', { name: 'Last run details' })).toContainText('tenant-a-stations')
})
