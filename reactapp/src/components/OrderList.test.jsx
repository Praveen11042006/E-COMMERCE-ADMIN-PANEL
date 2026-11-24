import React from 'react';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import OrderList from './OrderList';
import { renderWithRouter } from '../test/test-utils';

const fetchOrdersPaged = vi.fn();
const fetchOrderStats = vi.fn();
const updateOrderStatus = vi.fn();
const deleteOrder = vi.fn();
const navigate = vi.fn();

vi.mock('../utils/api', () => ({
  fetchOrdersPaged: (...args) => fetchOrdersPaged(...args),
  fetchOrderStats: (...args) => fetchOrderStats(...args),
  updateOrderStatus: (...args) => updateOrderStatus(...args),
  deleteOrder: (...args) => deleteOrder(...args),
  exportOrdersCsv: vi.fn(),
}));
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

const emptyStats = { total: 0, pending: 0, processing: 0, shipped: 0, delivered: 0, cancelled: 0, totalRevenue: 0, averageOrderValue: 0 };
const filledStats = { total: 1, pending: 1, processing: 0, shipped: 0, delivered: 0, cancelled: 0, totalRevenue: 250, averageOrderValue: 250 };

const pagedResponse = (orders) => ({
  data: { content: orders, totalPages: 1, totalElements: orders.length, number: 0, size: 8 },
});

const sampleOrder = {
  id: 42,
  customerName: 'Jane Doe',
  customerEmail: 'jane@example.test',
  shippingAddress: '1 Test St',
  orderDate: '2024-01-15T10:00:00',
  status: 'PENDING',
  totalAmount: 250,
  discountAmount: 0,
  items: [{ id: 1, productId: 1, productName: 'Widget', quantity: 2, priceAtPurchase: 125 }],
};

describe('OrderList', () => {
  beforeEach(() => {
    fetchOrdersPaged.mockReset();
    fetchOrderStats.mockReset();
    updateOrderStatus.mockReset();
    deleteOrder.mockReset();
    navigate.mockReset();
    fetchOrderStats.mockResolvedValue({ data: emptyStats });
  });

  it('renders an order card when the API returns data', async () => {
    fetchOrdersPaged.mockResolvedValue(pagedResponse([sampleOrder]));
    fetchOrderStats.mockResolvedValue({ data: filledStats });
    renderWithRouter(<OrderList />);
    expect(await screen.findByText('Jane Doe')).toBeInTheDocument();
    expect(screen.getByText('#42')).toBeInTheDocument();
    expect(screen.getByText('PENDING')).toBeInTheDocument();
  });

  it('shows empty state when no orders match', async () => {
    fetchOrdersPaged.mockResolvedValue(pagedResponse([]));
    renderWithRouter(<OrderList />);
    expect(await screen.findByText(/No orders yet/i)).toBeInTheDocument();
  });

  it('shows error state when the API fails', async () => {
    fetchOrdersPaged.mockRejectedValue(new Error('network'));
    renderWithRouter(<OrderList />);
    expect(await screen.findByText(/Failed to load orders/i)).toBeInTheDocument();
  });

  it('navigates to order detail on View Details click', async () => {
    fetchOrdersPaged.mockResolvedValue(pagedResponse([sampleOrder]));
    fetchOrderStats.mockResolvedValue({ data: filledStats });
    const user = userEvent.setup();
    renderWithRouter(<OrderList />);
    await user.click(await screen.findByTestId('view-button-42'));
    expect(navigate).toHaveBeenCalledWith('/admin/orders/42');
  });
});
