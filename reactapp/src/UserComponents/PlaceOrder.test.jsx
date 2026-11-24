import React from 'react';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import PlaceOrder from './PlaceOrder';
import { renderWithRouter } from '../test/test-utils';

const fetchProductsPaged = vi.fn();
const createOrder = vi.fn();
vi.mock('../utils/api', () => ({
  fetchProductsPaged: (...args) => fetchProductsPaged(...args),
  createOrder: (...args) => createOrder(...args),
  validateCoupon: vi.fn(),
  payOrder: vi.fn(),
  fetchInvoice: vi.fn(),
}));

const laptop = { id: 1, name: 'Laptop', description: 'Fast laptop', price: 100, category: 'Electronics', stockQuantity: 5 };
const mouse = { id: 2, name: 'Mouse', description: 'Wireless mouse', price: 50, category: 'Electronics', stockQuantity: 8 };

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

describe('PlaceOrder', () => {
  beforeEach(() => {
    fetchProductsPaged.mockReset();
    createOrder.mockReset();
    fetchProductsPaged.mockResolvedValue(page([laptop]));
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('adds, updates, removes cart items and calculates the subtotal', async () => {
    const user = userEvent.setup();
    renderWithRouter(<PlaceOrder />);
    await user.click(await screen.findByRole('button', { name: 'Add to Order' }));
    expect(screen.getAllByText('₹100').length).toBeGreaterThan(1);
    const quantity = screen.getByRole('spinbutton');
    fireEvent.change(quantity, { target: { value: '2' } });
    expect((await screen.findAllByText('₹200')).length).toBeGreaterThan(1);
    await user.click(screen.getByRole('button', { name: 'Remove' }));
    expect(screen.getByText('No items selected yet')).toBeInTheDocument();
  });

  it('submits required checkout data and shows API failures', async () => {
    createOrder.mockRejectedValue({ response: { data: { message: 'Stock changed' } } });
    const user = userEvent.setup();
    renderWithRouter(<PlaceOrder />);
    await user.click(await screen.findByRole('button', { name: 'Add to Order' }));
    await user.type(screen.getByPlaceholderText('Enter customer name'), 'Ada');
    await user.type(screen.getByPlaceholderText('customer@example.com'), 'ada@example.test');
    await user.type(screen.getByPlaceholderText('Enter complete shipping address'), '1 Test Lane');
    await user.click(screen.getByRole('button', { name: /Place Order/ }));
    await waitFor(() => expect(createOrder).toHaveBeenCalledWith(expect.objectContaining({
      customerName: 'Ada', customerEmail: 'ada@example.test', orderItems: [{ productId: 1, quantity: 1 }],
    })));
    expect(await screen.findByText(/Stock changed/)).toBeInTheDocument();
  });

  it('pages the catalogue on the server and keeps earlier cart lines', async () => {
    fetchProductsPaged
      .mockResolvedValueOnce(page([laptop], { totalPages: 2, last: false }))
      .mockResolvedValueOnce(page([mouse], { page: 1, totalPages: 2, first: false, last: true }));
    const user = userEvent.setup();
    renderWithRouter(<PlaceOrder />);

    await user.click(await screen.findByRole('button', { name: 'Add to Order' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() =>
      expect(fetchProductsPaged).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    );

    // Mouse is now available; the Laptop added on page 1 is still in the summary.
    await user.click(await screen.findByRole('button', { name: 'Add to Order' }));
    // Each product appears twice: once in the picker and once in the order summary.
    expect(screen.getAllByText('Laptop').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Mouse').length).toBeGreaterThan(0);

    await user.type(screen.getByPlaceholderText('Enter customer name'), 'Ada');
    await user.type(screen.getByPlaceholderText('customer@example.com'), 'ada@example.test');
    await user.type(screen.getByPlaceholderText('Enter complete shipping address'), '1 Test Lane');
    await user.click(screen.getByRole('button', { name: /Place Order/ }));

    await waitFor(() =>
      expect(createOrder).toHaveBeenCalledWith(
        expect.objectContaining({
          orderItems: [
            { productId: 1, quantity: 1 },
            { productId: 2, quantity: 1 },
          ],
          totalAmount: 150,
        })
      )
    );
  });
});
