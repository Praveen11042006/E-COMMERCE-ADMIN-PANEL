import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ProductList from './ProductList';
import { renderWithRouter } from '../test/test-utils';

const fetchProductsPaged = vi.fn();
const fetchProductStats = vi.fn();
const deleteProduct = vi.fn();
const updateProduct = vi.fn();

vi.mock('../utils/api', () => ({
  fetchProductsPaged: (...args) => fetchProductsPaged(...args),
  fetchProductStats: (...args) => fetchProductStats(...args),
  deleteProduct: (...args) => deleteProduct(...args),
  updateProduct: (...args) => updateProduct(...args),
}));

const widget = {
  id: 1,
  name: 'Widget',
  description: 'A widget',
  price: 100,
  category: 'Tools',
  stockQuantity: 4,
  imageUrl: '',
};

const page = (content, extra = {}) => ({
  data: {
    content,
    page: 0,
    size: 20,
    totalElements: 42,
    totalPages: 3,
    first: true,
    last: false,
    ...extra,
  },
});

describe('ProductList', () => {
  beforeEach(() => {
    fetchProductsPaged.mockReset();
    fetchProductStats.mockReset();
    deleteProduct.mockReset();
    updateProduct.mockReset();

    fetchProductsPaged.mockResolvedValue(page([widget]));
    fetchProductStats.mockResolvedValue({
      data: { totalProducts: 42, inStock: 40, outOfStock: 2, lowStock: 5, totalValue: 98765 },
    });
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('renders one page of products together with whole-table statistics', async () => {
    renderWithRouter(<ProductList />);

    expect(await screen.findByText('Widget')).toBeInTheDocument();
    expect(await screen.findByText('42')).toBeInTheDocument();
    expect(screen.getByText('Products (42)')).toBeInTheDocument();
    expect(screen.getByText(/Showing 1–1 of 42 products/)).toBeInTheDocument();
  });

  it('requests the next page from the server', async () => {
    fetchProductsPaged.mockResolvedValue(page([widget], { page: 1, first: false }));
    const user = userEvent.setup();
    renderWithRouter(<ProductList />);
    await screen.findByText('Widget');

    await user.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() =>
      expect(fetchProductsPaged).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 20 }))
    );
  });

  it('sends the search term to the server instead of filtering in memory', async () => {
    const user = userEvent.setup();
    renderWithRouter(<ProductList />);
    await screen.findByText('Widget');

    await user.click(screen.getByRole('button', { name: /Filters & Search/i }));
    await user.type(screen.getByPlaceholderText('Search products...'), 'wid');

    await waitFor(
      () => expect(fetchProductsPaged).toHaveBeenLastCalledWith(expect.objectContaining({ search: 'wid' })),
      { timeout: 2000 }
    );
  });

  it('deletes a product and refreshes the page and the statistics', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    deleteProduct.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithRouter(<ProductList />);
    await screen.findByText('Widget');
    const callsBefore = fetchProductStats.mock.calls.length;

    await user.click(screen.getByRole('button', { name: /Delete/i }));

    await waitFor(() => expect(deleteProduct).toHaveBeenCalledWith(1));
    await waitFor(() => expect(fetchProductStats.mock.calls.length).toBeGreaterThan(callsBefore));
    confirmSpy.mockRestore();
  });
});
