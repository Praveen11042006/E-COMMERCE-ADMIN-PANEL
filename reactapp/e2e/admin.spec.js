import { test, expect } from '@playwright/test';
import { ADMIN, login } from './helpers';

test.describe('admin', () => {
  test('creates and edits a coupon', async ({ page }) => {
    await login(page, ADMIN, '/admin');
    await page.goto('/admin/coupons');
    await expect(page.getByRole('heading', { name: 'Coupons', exact: true })).toBeVisible();

    const code = `E2E${Date.now().toString().slice(-6)}`;
    await page.getByLabel('Code', { exact: true }).fill(code);
    await page.getByLabel('Discount value', { exact: true }).fill('10');
    await page.getByRole('button', { name: 'Create coupon' }).click();

    await expect(page.getByText(`Coupon ${code} created successfully`)).toBeVisible();
    const row = page.getByRole('row').filter({ hasText: code });
    await expect(row).toBeVisible();
    await expect(row).toContainText('10%');

    await row.getByRole('button', { name: `Edit coupon ${code}` }).click();
    await expect(page.getByText(`Edit coupon ${code}`)).toBeVisible();
    await page.getByLabel('Discount value', { exact: true }).fill('15');
    await page.getByRole('button', { name: 'Save changes' }).click();

    await expect(page.getByText(`Coupon ${code} updated successfully`)).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: code })).toContainText('15%');
  });

  test('analytics dashboard loads aggregated data without errors', async ({ page }) => {
    await login(page, ADMIN, '/admin');
    await page.goto('/admin/analytics');

    await expect(page.getByText('Analytics Dashboard').first()).toBeVisible();
    await expect(page.getByText('Top Products')).toBeVisible();
    await expect(page.getByText(/Failed to load data/i)).toHaveCount(0);
    // Aggregated KPI card rendered from /api/analytics/order-metrics.
    await expect(page.getByText('Total Orders').first()).toBeVisible();
  });
});
