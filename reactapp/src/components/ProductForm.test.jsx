import React from 'react';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import ProductForm from './ProductForm';
import { renderWithRouter } from '../test/test-utils';

const createProduct = vi.fn();
const navigate = vi.fn();
vi.mock('../utils/api', () => ({ createProduct: (...args) => createProduct(...args) }));
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

describe('ProductForm', () => {
  it('submits normalized numeric product fields', async () => {
    createProduct.mockResolvedValue({ data: { id: 1 } });
    const user = userEvent.setup();
    renderWithRouter(<ProductForm />);
    await user.type(screen.getByLabelText(/Product Name/), 'Keyboard');
    await user.type(screen.getByLabelText(/Description/), 'Mechanical');
    await user.type(screen.getByLabelText(/Price/), '99.5');
    await user.type(screen.getByLabelText(/Category/), 'Electronics');
    await user.type(screen.getByLabelText(/Stock Quantity/), '4');
    await user.click(screen.getByRole('button', { name: 'Save Product' }));
    expect(createProduct).toHaveBeenCalledWith(expect.objectContaining({ price: 99.5, stockQuantity: 4 }));
    expect(navigate).toHaveBeenCalledWith('/admin/products');
  });
});
