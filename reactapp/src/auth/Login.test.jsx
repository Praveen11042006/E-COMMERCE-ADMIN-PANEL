import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import Login from './Login';
import { renderWithRouter } from '../test/test-utils';

const navigate = vi.fn();
const loginUser = vi.fn();
vi.mock('../utils/api', () => ({ loginUser: (...args) => loginUser(...args) }));
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

describe('Login', () => {
  beforeEach(() => { navigate.mockReset(); loginUser.mockReset(); });

  it('renders required credentials and submits a successful admin login', async () => {
    loginUser.mockResolvedValue({ token: 'test-token', role: 'ADMIN', userId: 7 });
    const user = userEvent.setup();
    renderWithRouter(<Login />);
    await user.type(screen.getByPlaceholderText('Email'), 'admin@example.test');
    await user.type(screen.getByPlaceholderText('Password'), 'Password@123');
    await user.click(screen.getByRole('button', { name: 'Login' }));
    await waitFor(() => expect(loginUser).toHaveBeenCalledWith('admin@example.test', 'Password@123'));
    expect(navigate).toHaveBeenCalledWith('/admin/products');
  });

  it('shows the API error without navigating', async () => {
    loginUser.mockRejectedValue({ response: { data: { message: 'Account is deactivated.' } } });
    const user = userEvent.setup();
    renderWithRouter(<Login />);
    await user.type(screen.getByPlaceholderText('Email'), 'user@example.test');
    await user.type(screen.getByPlaceholderText('Password'), 'Password@123');
    await user.click(screen.getByRole('button', { name: 'Login' }));
    expect(await screen.findByText('Account is deactivated.')).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });
});
