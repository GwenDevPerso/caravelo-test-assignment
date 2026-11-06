import { expect, test } from '@playwright/test'

test.describe('mobile layout', () => {
  test.skip(({ isMobile }) => !isMobile, 'mobile project only')

  for (const path of ['/tenants/tenant-a/routes', '/tenants/tenant-a/health']) {
    test(`no horizontal scroll on ${path}`, async ({ page }) => {
      await page.goto(path)
      await expect(page.getByRole('heading', { name: 'tenant-a' })).toBeVisible()
      await expect(page.getByRole('status')).toHaveCount(0) // loaded, not "Loading…"
      const overflow = await page.evaluate(() => {
        const root = document.documentElement
        return { scrollWidth: root.scrollWidth, clientWidth: root.clientWidth }
      })
      expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth)
    })
  }
})
