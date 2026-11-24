import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import CouponList from './CouponList';
import { renderWithRouter } from '../test/test-utils';

const fetchCoupons = vi.fn();
const createCoupon = vi.fn();
const updateCoupon = vi.fn();
const deleteCoupon = vi.fn();

vi.mock('../utils/api', () => ({
  fetchCoupons: (...args) => fetchCoupons(...args),
  createCoupon: (...args) => createCoupon(...args),
  updateCoupon: (...args) => updateCoupon(...args),
  deleteCoupon: (...args) => deleteCoupon(...args),
}));

const sampleCoupon = {
  id: 1,
  code: 'SAVE10',
  discountType: 'PERCENTAGE',
  discountValue: 10,
  maxDiscount: 50,
  minOrderAmount: 100,
  usageLimit: 25,
  usedCount: 3,
  expiryDate: '2099-01-01T00:00:00',
  active: true,
};

describe('CouponList', () => {
  beforeEach(() => {
    fetchCoupons.mockReset();
    createCoupon.mockReset();
    updateCoupon.mockReset();
    deleteCoupon.mockReset();
    fetchCoupons.mockResolvedValue({ data: [sampleCoupon] });
  });

  it('lists coupons returned by the API', async () => {
    renderWithRouter(<CouponList />);
    expect(await screen.findByText('SAVE10')).toBeInTheDocument();
    expect(screen.getByText('10% (max ₹50.00)')).toBeInTheDocument();
    expect(screen.getByText('3 / 25')).toBeInTheDocument();
  });

  it('shows an error when coupons cannot be loaded', async () => {
    fetchCoupons.mockRejectedValue(new Error('network'));
    renderWithRouter(<CouponList />);
    expect(await screen.findByText(/Failed to load coupons/i)).toBeInTheDocument();
  });

  it('rejects a percentage above 100 before calling the API', async () => {
    const user = userEvent.setup();
    renderWithRouter(<CouponList />);
    await screen.findByText('SAVE10');

    await user.type(screen.getByLabelText('Code'), 'SAVE20');
    await user.clear(screen.getByLabelText('Discount value'));
    await user.type(screen.getByLabelText('Discount value'), '150');
    await user.click(screen.getByRole('button', { name: /Create coupon/i }));

    expect(await screen.findByText('Percentage discount cannot exceed 100')).toBeInTheDocument();
    expect(createCoupon).not.toHaveBeenCalled();
  });

  it('creates a valid coupon and shows a success message', async () => {
    const user = userEvent.setup();
    createCoupon.mockResolvedValue({ data: { ...sampleCoupon, id: 2, code: 'WELCOME' } });
    renderWithRouter(<CouponList />);
    await screen.findByText('SAVE10');

    await user.type(screen.getByLabelText('Code'), 'welcome');
    await user.clear(screen.getByLabelText('Discount value'));
    await user.type(screen.getByLabelText('Discount value'), '10');
    await user.click(screen.getByRole('button', { name: /Create coupon/i }));

    await waitFor(() => expect(createCoupon).toHaveBeenCalledTimes(1));
    expect(createCoupon.mock.calls[0][0]).toMatchObject({
      code: 'welcome',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      minOrderAmount: 0,
      maxDiscount: 0,
      usageLimit: 0,
    });
    expect(await screen.findByText(/Coupon WELCOME created successfully/i)).toBeInTheDocument();
  });

  it('surfaces backend field errors returned on create', async () => {
    const user = userEvent.setup();
    createCoupon.mockRejectedValue({
      response: {
        data: {
          message: 'Validation failed',
          errors: { code: 'Coupon code already exists' },
        },
      },
    });
    renderWithRouter(<CouponList />);
    await screen.findByText('SAVE10');

    await user.type(screen.getByLabelText('Code'), 'SAVE10');
    await user.clear(screen.getByLabelText('Discount value'));
    await user.type(screen.getByLabelText('Discount value'), '5');
    await user.click(screen.getByRole('button', { name: /Create coupon/i }));

    expect(await screen.findByText('Validation failed')).toBeInTheDocument();
    expect(await screen.findByText('Coupon code already exists')).toBeInTheDocument();
  });

  it('deletes a coupon after confirmation', async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    deleteCoupon.mockResolvedValue({});
    renderWithRouter(<CouponList />);
    await screen.findByText('SAVE10');

    await user.click(screen.getByRole('button', { name: /Delete coupon SAVE10/i }));

    await waitFor(() => expect(deleteCoupon).toHaveBeenCalledWith(1));
    expect(await screen.findByText('Coupon SAVE10 deleted')).toBeInTheDocument();
    confirmSpy.mockRestore();
  });

  it('edits an existing coupon without sending the usage counter', async () => {
    const user = userEvent.setup();
    updateCoupon.mockResolvedValue({ data: { ...sampleCoupon, code: 'SAVE20', discountValue: 20 } });
    renderWithRouter(<CouponList />);
    await screen.findByText('SAVE10');

    await user.click(screen.getByRole('button', { name: /Edit coupon SAVE10/i }));
    expect(screen.getByText('Edit coupon SAVE10')).toBeInTheDocument();
    expect(screen.getByLabelText('Code')).toHaveValue('SAVE10');
    expect(screen.getByLabelText('Discount value')).toHaveValue(10);

    await user.clear(screen.getByLabelText('Code'));
    await user.type(screen.getByLabelText('Code'), 'save20');
    await user.clear(screen.getByLabelText('Discount value'));
    await user.type(screen.getByLabelText('Discount value'), '20');
    await user.click(screen.getByRole('button', { name: /Save changes/i }));

    await waitFor(() => expect(updateCoupon).toHaveBeenCalledTimes(1));
    expect(updateCoupon.mock.calls[0][0]).toBe(1);
    expect(updateCoupon.mock.calls[0][1]).toMatchObject({
      code: 'save20',
      discountType: 'PERCENTAGE',
      discountValue: 20,
      minOrderAmount: 100,
      maxDiscount: 50,
      usageLimit: 25,
    });
    expect(updateCoupon.mock.calls[0][1]).not.toHaveProperty('usedCount');
    expect(await screen.findByText(/Coupon SAVE20 updated successfully/i)).toBeInTheDocument();
  });
});
