import { test, expect } from '@playwright/test';
import { CUSTOMER, login } from './helpers';

test.describe('authentication and authorization', () => {
  test('unauthenticated admin area redirects to the login page', async ({ page }) => {
    await page.goto('/admin/products');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Login' })).toBeVisible();
  });

  test('invalid credentials show an error and stay on the login page', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('Email').fill(CUSTOMER.email);
    await page.getByPlaceholder('Password').fill('WrongPassword1!');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByText(/Invalid email or password/i)).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
  });

  test('a customer cannot enter the admin area', async ({ page }) => {
    await login(page, CUSTOMER, '/user');
    await page.goto('/admin/products');
    await expect(page).toHaveURL(/\/user/);
    await expect(page.getByRole('heading', { name: 'Products' })).toHaveCount(0);
  });
});

test.describe('customer shopping', () => {
  test('browses the catalogue and pages through it', async ({ page }) => {
    await login(page, CUSTOMER, '/user');
    await page.goto('/user/products');

    await expect(page.getByRole('heading', { name: 'E2E Product 01' })).toBeVisible();
    // Page size is 12, so the 13th product lives on the second page.
    await expect(page.getByRole('heading', { name: 'E2E Product 13' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Next' }).click();
    await expect(page.getByRole('heading', { name: 'E2E Product 13' })).toBeVisible();
  });

  test('checks out, pays, and sees real payment states in order history', async ({ page }) => {
    await login(page, CUSTOMER, '/user');
    await page.goto('/user/place-order');

    await expect(page.getByRole('heading', { name: 'E2E Product 01' })).toBeVisible();
    await page.getByRole('button', { name: 'Add to Order' }).first().click();
    await page.getByPlaceholder('Enter customer name').fill('E2E Buyer');
    await page.getByPlaceholder('customer@example.com').fill('buyer@e2e.test');
    await page.getByPlaceholder('Enter complete shipping address').fill('9 E2E Avenue');
    await page.getByRole('button', { name: /Place Order/ }).click();

    await expect(page.getByText('Order placed successfully')).toBeVisible();
    await page.getByRole('button', { name: /Pay Now/ }).click();
    await expect(page.getByText('Payment successful')).toBeVisible();

    await page.getByRole('button', { name: 'View My Orders' }).click();
    await expect(page.getByRole('heading', { name: 'My Orders' })).toBeVisible();
    // The freshly paid order and the seeded unpaid order.
    await expect(page.getByText('Payment: SUCCESS').first()).toBeVisible();
    await expect(page.getByText('No payment recorded').first()).toBeVisible();
  });
});
