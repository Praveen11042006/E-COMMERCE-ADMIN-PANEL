import React from 'react';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import UserOrderItem from './UserOrderItem';
import { renderWithRouter } from '../test/test-utils';

vi.mock('../utils/api.js', () => ({
  cancelMyOrder: vi.fn(),
}));

const baseOrder = {
  id: 12,
  status: 'PENDING',
  orderDate: '2026-02-01T09:00:00',
  totalAmount: 300,
  discountAmount: 0,
  orderItems: [{ id: 1, quantity: 1, priceAtPurchase: 300, product: { name: 'Gadget' } }],
};

const payment = (status) => ({
  status,
  method: 'MOCK',
  amount: 300,
  transactionRef: 'MOCK-REF1',
  invoiceNumber: 'INV-2026-00012',
  createdAt: '2026-02-01T09:05:00',
});

describe('UserOrderItem payment visibility', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it.each([
    ['SUCCESS', 'Payment: SUCCESS'],
    ['PENDING', 'Payment: PENDING'],
    ['FAILED', 'Payment: FAILED'],
  ])('renders the stored %s status in the order card', (status, label) => {
    renderWithRouter(
      <UserOrderItem order={{ ...baseOrder, payment: payment(status) }} onCancel={() => {}} />
    );
    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it('shows "No payment recorded" when the order has no payment row', () => {
    renderWithRouter(<UserOrderItem order={{ ...baseOrder, payment: null }} onCancel={() => {}} />);
    expect(screen.getByText('No payment recorded')).toBeInTheDocument();
  });

  it('shows only stored payment fields in the expanded details', async () => {
    const user = userEvent.setup();
    renderWithRouter(
      <UserOrderItem order={{ ...baseOrder, payment: payment('SUCCESS') }} onCancel={() => {}} />
    );

    await user.click(screen.getByRole('button', { name: 'View details' }));

    const paymentSection = screen.getByText('Payment').closest('div');
    expect(within(paymentSection).getByText('MOCK')).toBeInTheDocument();
    expect(within(paymentSection).getByText('MOCK-REF1')).toBeInTheDocument();
    expect(within(paymentSection).getByText('INV-2026-00012')).toBeInTheDocument();
    expect(within(paymentSection).getByText('₹300')).toBeInTheDocument();
  });

  it('explains a missing payment in the expanded details', async () => {
    const user = userEvent.setup();
    renderWithRouter(<UserOrderItem order={{ ...baseOrder, payment: null }} onCancel={() => {}} />);

    await user.click(screen.getByRole('button', { name: 'View details' }));
    expect(screen.getByText(/No payment has been recorded for this order yet/i)).toBeInTheDocument();
  });
});
