import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import UserProductList from './UserProductList';
import { renderWithRouter } from '../test/test-utils';

const fetchProductsPaged = vi.fn();
const fetchRecommendations = vi.fn();

vi.mock('../utils/api.js', () => ({
  fetchProductsPaged: (...args) => fetchProductsPaged(...args),
  fetchRecommendations: (...args) => fetchRecommendations(...args),
}));

const pageResponse = (overrides = {}) => ({
  data: {
    content: [{ id: 1, name: 'Laptop', price: 500, category: 'Electronics', stockQuantity: 2 }],
    page: 0,
    size: 12,
    totalElements: 3,
    totalPages: 3,
    first: true,
    last: false,
    ...overrides,
  },
});

describe('UserProductList', () => {
  beforeEach(() => {
    fetchProductsPaged.mockReset();
    fetchRecommendations.mockReset();
    fetchProductsPaged.mockResolvedValue(pageResponse());
    fetchRecommendations.mockResolvedValue({
      data: { recommendations: [{ productId: 2, name: 'Mouse', price: 20, reason: 'Popular product' }] },
    });
  });

  it('renders the paged products and recommendations returned by the API', async () => {
    renderWithRouter(<UserProductList />);
    expect(await screen.findByText('Laptop')).toBeInTheDocument();
    expect(screen.getByText('Recommended for you')).toBeInTheDocument();
    expect(screen.getByText('Mouse')).toBeInTheDocument();
    expect(fetchProductsPaged).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, size: 12 })
    );
  });

  it('shows an error when product loading fails', async () => {
    fetchProductsPaged.mockRejectedValue(new Error('network'));
    renderWithRouter(<UserProductList />);
    expect(await screen.findByText('Failed to load products. Please try again later.')).toBeInTheDocument();
  });

  it('requests the next page from the server', async () => {
    const user = userEvent.setup();
    renderWithRouter(<UserProductList />);
    await screen.findByText('Laptop');

    await user.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() =>
      expect(fetchProductsPaged).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    );
  });

  it('does not refetch when the untouched filter set is re-applied after mount', async () => {
    renderWithRouter(<UserProductList />);
    await screen.findByText('Laptop');

    // Let the debounce window elapse with no filter change.
    await new Promise((resolve) => setTimeout(resolve, 500));

    expect(fetchProductsPaged).toHaveBeenCalledTimes(1);
  });

  it('sends the search term to the server instead of filtering in memory', async () => {
    const user = userEvent.setup();
    renderWithRouter(<UserProductList />);
    await screen.findByText('Laptop');

    await user.type(screen.getByLabelText('Search products'), 'phone');

    await waitFor(
      () => expect(fetchProductsPaged).toHaveBeenLastCalledWith(expect.objectContaining({ search: 'phone' })),
      { timeout: 2000 }
    );
  });

  it('rejects an inverted price range before calling the API', async () => {
    const user = userEvent.setup();
    renderWithRouter(<UserProductList />);
    await screen.findByText('Laptop');

    await user.type(screen.getByLabelText('Minimum price'), '900');
    await user.type(screen.getByLabelText('Maximum price'), '100');

    expect(
      await screen.findByText('Minimum price cannot be greater than maximum price.', {}, { timeout: 2000 })
    ).toBeInTheDocument();
    // The invalid combination must never reach the API.
    expect(
      fetchProductsPaged.mock.calls.every(([params]) => params.maxPrice !== '100')
    ).toBe(true);
  });

  it('shows pagination totals from the server response', async () => {
    renderWithRouter(<UserProductList />);
    await screen.findByText('Laptop');
    expect(screen.getByText('Showing 1–1 of 3')).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });
});
