import { expect, test } from '@playwright/test'

test('changes page renders the change log', async ({ page }) => {
  await page.goto('/tenants/tenant-a/changes')
  const firstLog = page.getByRole('table').first()
  await expect(firstLog).toBeVisible()
  await expect(firstLog.getByRole('row').nth(1)).toContainText(/ADDED|REMOVED|MODIFIED/)
})
