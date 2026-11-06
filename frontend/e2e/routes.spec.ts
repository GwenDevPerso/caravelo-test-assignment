import { expect, test } from '@playwright/test'

const acaRow = (page: import('@playwright/test').Page) => page.getByRole('row', { name: /^ACA\b/ })

test('origin ACA lists its 58 destinations', async ({ page }) => {
  await page.goto('/tenants/tenant-a/routes?origin=ACA')
  await expect(acaRow(page).getByRole('listitem')).toHaveCount(58)
})

test('filters go to the query string, survive a reload and keep only DIRECT routes', async ({ page }) => {
  await page.goto('/tenants/tenant-a/routes')
  await page.getByLabel('Origin').fill('aca')
  await page.getByLabel('Type').selectOption('DIRECT')
  await page.getByRole('button', { name: './filter →' }).click()

  await expect(page).toHaveURL(/\?origin=ACA&type=DIRECT$/)
  await expect(acaRow(page).getByRole('listitem')).toHaveText(['GDL', 'MEX', 'TIJ'])

  await page.reload()
  await expect(page.getByLabel('Origin')).toHaveValue('ACA')
  await expect(page.getByLabel('Type')).toHaveValue('DIRECT')
  await expect(acaRow(page).getByRole('listitem')).toHaveText(['GDL', 'MEX', 'TIJ'])
})

test('invalid origin shows the API 400 on the origin field', async ({ page }) => {
  await page.goto('/tenants/tenant-a/routes')
  await page.getByLabel('Origin').fill('A1')
  await page.getByRole('button', { name: './filter →' }).click()

  const origin = page.getByLabel('Origin')
  await expect(origin).toHaveAttribute('aria-invalid', 'true')
  await expect(origin).toHaveAccessibleDescription('Invalid IATA code for origin: A1')
})
