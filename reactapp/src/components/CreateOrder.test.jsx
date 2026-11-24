import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import CreateOrder from './CreateOrder';
import { renderWithRouter } from '../test/test-utils';

const fetchProductsPaged = vi.fn();
const createOrder = vi.fn();

vi.mock('../utils/api', () => ({
  fetchProductsPaged: (...args) => fetchProductsPaged(...args),
  createOrder: (...args) => createOrder(...args),
}));

const widget = { id: 7, name: 'Widget', description: 'A widget', price: 250, category: 'Tools', stockQuantity: 3 };

const page = (content, extra = {}) => ({
  data: {
    content,
    page: 0,
    size: 12,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
    ...extra,
  },
});

describe('CreateOrder', () => {
  beforeEach(() => {
    fetchProductsPaged.mockReset();
    createOrder.mockReset();
    fetchProductsPaged.mockResolvedValue(page([widget]));
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('renders products from the paged endpoint', async () => {
    renderWithRouter(<CreateOrder />);
    expect(await screen.findByText('Widget')).toBeInTheDocument();
    expect(fetchProductsPaged).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 12 }));
  });

  it('creates an order with the selected product', async () => {
    createOrder.mockResolvedValue({ data: { id: 1 } });
    const user = userEvent.setup();
    renderWithRouter(<CreateOrder />);

    await user.click(await screen.findByRole('button', { name: 'Add to Order' }));
    await user.type(screen.getByPlaceholderText('Enter customer name'), 'Grace');
    await user.type(screen.getByPlaceholderText('customer@example.com'), 'grace@example.test');
    await user.type(screen.getByPlaceholderText('Enter complete shipping address'), '2 Test Rd');
    await user.click(screen.getByRole('button', { name: /Place Order/ }));

    await waitFor(() =>
      expect(createOrder).toHaveBeenCalledWith(
        expect.objectContaining({
          customerName: 'Grace',
          orderItems: [{ productId: 7, quantity: 1 }],
          totalAmount: 250,
        })
      )
    );
  });

  it('requests the next page from the server', async () => {
    fetchProductsPaged.mockResolvedValue(page([widget], { totalPages: 2, last: false }));
    const user = userEvent.setup();
    renderWithRouter(<CreateOrder />);
    await screen.findByText('Widget');

    await user.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() =>
      expect(fetchProductsPaged).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    );
  });
});
