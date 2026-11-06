import { expect, test } from '@playwright/test'

test('tenants page lists the configured tenants', async ({ page }) => {
  await page.goto('/')
  for (const tenant of ['tenant-a', 'tenant-b', 'tenant-c']) {
    await expect(page.getByRole('link', { name: `${tenant} →` })).toBeVisible()
  }
})

test('unknown tenant shows the API not-found message', async ({ page }) => {
  await page.goto('/tenants/no-such-tenant/routes')
  await expect(page.getByRole('status')).toContainText('Unknown tenant: no-such-tenant')
})

test('unknown path shows the 404 page', async ({ page }) => {
  await page.goto('/does/not/exist')
  await expect(page.getByRole('heading', { name: 'Nothing here' })).toBeVisible()
  await expect(page.getByText('This page does not exist.')).toBeVisible()
})
