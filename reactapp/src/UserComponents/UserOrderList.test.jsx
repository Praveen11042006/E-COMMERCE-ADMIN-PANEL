import React from 'react';
import { screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import UserOrderList from './UserOrderList';
import { renderWithRouter } from '../test/test-utils';

const fetchMyOrdersPaged = vi.fn();
vi.mock('../utils/api.js', () => ({
  fetchMyOrdersPaged: (...args) => fetchMyOrdersPaged(...args),
  cancelMyOrder: vi.fn(),
}));

const pagedResponse = (orders) => ({
  data: { content: orders, totalPages: 1, totalElements: orders.length, number: 0, size: 10 },
});

const sampleOrder = {
  id: 7,
  customerName: 'Alice',
  customerEmail: 'alice@example.test',
  shippingAddress: '2 Test Ave',
  orderDate: '2024-02-01T09:00:00',
  status: 'PENDING',
  totalAmount: 300,
  discountAmount: 0,
  items: [{ id: 1, productId: 1, productName: 'Gadget', quantity: 1, priceAtPurchase: 300 }],
};

describe('UserOrderList', () => {
  beforeEach(() => { fetchMyOrdersPaged.mockReset(); });

  it('renders user orders returned by the API', async () => {
    fetchMyOrdersPaged.mockResolvedValue(pagedResponse([sampleOrder]));
    renderWithRouter(<UserOrderList />);
    expect(await screen.findByText(/Order #7/i)).toBeInTheDocument();
  });

  it('shows empty state when the user has no orders', async () => {
    fetchMyOrdersPaged.mockResolvedValue(pagedResponse([]));
    renderWithRouter(<UserOrderList />);
    expect(await screen.findByText(/no orders yet/i)).toBeInTheDocument();
  });

  it('shows error message when the API fails', async () => {
    fetchMyOrdersPaged.mockRejectedValue(new Error('network'));
    renderWithRouter(<UserOrderList />);
    expect(await screen.findByText(/Failed to load orders/i)).toBeInTheDocument();
  });
});
