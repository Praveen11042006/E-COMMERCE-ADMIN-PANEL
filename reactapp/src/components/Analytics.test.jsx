import React from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import Analytics from './Analytics';
import { renderWithRouter } from '../test/test-utils';

const fetchInventory = vi.fn();
const fetchCategorySales = vi.fn();
const fetchOrderMetrics = vi.fn();
const fetchTimeSeries = vi.fn();

vi.mock('../utils/api', () => ({
  fetchInventory: (...args) => fetchInventory(...args),
  fetchCategorySales: (...args) => fetchCategorySales(...args),
  fetchOrderMetrics: (...args) => fetchOrderMetrics(...args),
  fetchTimeSeries: (...args) => fetchTimeSeries(...args),
}));

// Recharts uses ResizeObserver — provide a minimal stub for jsdom
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

const sampleInventory = {
  totalProducts: 2,
  lowStockProducts: [
    { id: 9, name: 'Cable', category: 'Accessories', stockQuantity: 4, price: 40 },
  ],
};

const sampleMetrics = {
  revenue: 100,
  orders: 1,
  customers: 1,
  averageOrderValue: 100,
  growthRate: 0,
  statusDistribution: [{ status: 'DELIVERED', count: 1 }],
  topProducts: [{ id: 1, name: 'Widget', category: 'Electronics', quantity: 1, revenue: 100 }],
  topCustomers: [{ name: 'Eve', email: 'eve@example.test', totalSpent: 100, totalOrders: 1 }],
  recentOrders: [{ id: 1, customerName: 'Eve', status: 'DELIVERED', totalAmount: 100, orderDate: '2026-02-01T09:00:00', itemCount: 1 }],
  repeatCustomers: 0,
  avgOrdersPerCustomer: 1,
  customerLifetimeValue: 100,
};

const sampleTimeSeries = [
  { orderDate: '2026-02-01T09:00:00', totalAmount: 100, customerEmail: 'eve@example.test' },
];

const sampleCategorySales = [{ category: 'Electronics', revenue: 100, quantity: 1 }];

describe('Analytics', () => {
  beforeEach(() => {
    fetchInventory.mockReset();
    fetchCategorySales.mockReset();
    fetchOrderMetrics.mockReset();
    fetchTimeSeries.mockReset();

    fetchInventory.mockResolvedValue({ data: sampleInventory });
    fetchCategorySales.mockResolvedValue({ data: sampleCategorySales });
    fetchOrderMetrics.mockResolvedValue({ data: sampleMetrics });
    fetchTimeSeries.mockResolvedValue({ data: sampleTimeSeries });
    vi.spyOn(console, 'log').mockImplementation(() => {});
  });

  it('shows loading spinner before data arrives', () => {
    fetchOrderMetrics.mockReturnValue(new Promise(() => {}));
    fetchTimeSeries.mockReturnValue(new Promise(() => {}));
    renderWithRouter(<Analytics />);
    expect(screen.getByText(/Loading Analytics/i)).toBeInTheDocument();
  });

  it('shows error message when the API fails', async () => {
    fetchOrderMetrics.mockRejectedValue(new Error('db error'));
    renderWithRouter(<Analytics />);
    expect(await screen.findByText(/Failed to load data/i)).toBeInTheDocument();
  });

  it('renders the Analytics Dashboard heading after data loads', async () => {
    renderWithRouter(<Analytics />);
    expect((await screen.findAllByText('Analytics Dashboard')).length).toBeGreaterThan(0);
  });

  it('renders aggregated KPI and table data without fetching orders', async () => {
    renderWithRouter(<Analytics />);
    await screen.findAllByText('Analytics Dashboard');

    await waitFor(() => {
      const card = screen.getByText('Total Orders').closest('.bg-white');
      expect(card).not.toBeNull();
      expect(within(card).getByText('1')).toBeInTheDocument();
    });

    expect(await screen.findByText('Widget')).toBeInTheDocument();
    expect(screen.getAllByText('Eve').length).toBeGreaterThan(0);
    expect(await screen.findByText('Cable')).toBeInTheDocument();

    // Whole-history view: no date window on any analytics request.
    expect(fetchOrderMetrics).toHaveBeenCalledWith({});
    expect(fetchCategorySales).toHaveBeenCalledWith({});
    expect(fetchTimeSeries).toHaveBeenCalledWith({});
  });

  it('re-requests every filter-dependent endpoint with a date window', async () => {
    renderWithRouter(<Analytics />);
    await screen.findAllByText('Analytics Dashboard');

    await userEvent.setup().selectOptions(screen.getByRole('combobox'), 'today');

    await waitFor(() =>
      expect(fetchOrderMetrics).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: expect.any(Number), to: expect.any(Number), days: 1 })
      )
    );
    await waitFor(() =>
      expect(fetchTimeSeries).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: expect.any(Number), to: expect.any(Number) })
      )
    );
    await waitFor(() =>
      expect(fetchCategorySales).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: expect.any(Number), to: expect.any(Number) })
      )
    );
  });

  it('drops a stale dashboard response that arrives after a newer filter', async () => {
    const user = userEvent.setup();
    renderWithRouter(<Analytics />);
    await screen.findAllByText('Analytics Dashboard');

    // The next ("week") request stays pending; the one after it ("month") wins.
    let resolveWeek;
    fetchOrderMetrics
      .mockReturnValueOnce(new Promise((resolve) => { resolveWeek = resolve; }))
      .mockResolvedValueOnce({ data: { ...sampleMetrics, orders: 7 } });

    await user.selectOptions(screen.getByRole('combobox'), 'week');
    await user.selectOptions(screen.getByRole('combobox'), 'month');

    await waitFor(() => {
      const card = screen.getByText('Total Orders').closest('.bg-white');
      expect(within(card).getByText('7')).toBeInTheDocument();
    });

    // The older request finally answers with stale data — it must be ignored.
    resolveWeek({ data: { ...sampleMetrics, orders: 1 } });
    await new Promise((r) => setTimeout(r, 20));
    const card = screen.getByText('Total Orders').closest('.bg-white');
    expect(within(card).getByText('7')).toBeInTheDocument();
  });
});
