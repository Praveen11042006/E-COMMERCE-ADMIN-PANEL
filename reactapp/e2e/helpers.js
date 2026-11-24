import { expect } from '@playwright/test';

export const ADMIN = { email: 'admin@e2e.test', password: 'Admin@12345' };
export const CUSTOMER = { email: 'customer@e2e.test', password: 'Customer@12345' };

/** Logs in through the real login form and waits for the role dashboard. */
export async function login(page, { email, password }, expectedPath) {
  await page.goto('/login');
  await page.getByPlaceholder('Email').fill(email);
  await page.getByPlaceholder('Password').fill(password);
  await page.getByRole('button', { name: 'Login' }).click();
  await page.waitForURL(`**${expectedPath}**`);
  await expect(page.locator('body')).not.toContainText('Invalid email or password');
}
