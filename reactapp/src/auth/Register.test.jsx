import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import Register from './Register';
import { renderWithRouter } from '../test/test-utils';

const registerUser = vi.fn();
const navigate = vi.fn();
vi.mock('../utils/api', () => ({ registerUser: (...args) => registerUser(...args) }));
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

describe('Register', () => {
  beforeEach(() => { registerUser.mockReset(); navigate.mockReset(); });

  it('rejects a weak password before calling the API', async () => {
    const user = userEvent.setup();
    renderWithRouter(<Register />);
    await user.type(screen.getByPlaceholderText('Full Name'), 'Alice');
    await user.type(screen.getByPlaceholderText('Email'), 'alice@example.test');
    await user.type(screen.getByPlaceholderText('Password'), 'weakpass');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByText(/uppercase/i)).toBeInTheDocument();
    expect(registerUser).not.toHaveBeenCalled();
  });

  it('shows success message and navigates to login on valid registration', async () => {
    registerUser.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithRouter(<Register />);
    await user.type(screen.getByPlaceholderText('Full Name'), 'Alice');
    await user.type(screen.getByPlaceholderText('Email'), 'alice@example.test');
    await user.type(screen.getByPlaceholderText('Password'), 'Password@123');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    await waitFor(() =>
      expect(screen.getByText(/Registration successful/i)).toBeInTheDocument()
    );
    expect(registerUser).toHaveBeenCalledWith('Alice', 'alice@example.test', 'Password@123');
    // The component redirects after a 1.5s delay; wait for it here so that timer
    // cannot fire during the next test and pollute its navigate assertions.
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/login'), { timeout: 3000 });
  });

  it.each(['Str0ng#Pass', 'Str0ng^Pass'])('accepts the legitimate special character in %s', async (password) => {
    registerUser.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithRouter(<Register />);
    await user.type(screen.getByPlaceholderText('Full Name'), 'Alice');
    await user.type(screen.getByPlaceholderText('Email'), 'alice@example.test');
    await user.type(screen.getByPlaceholderText('Password'), password);
    await user.click(screen.getByRole('button', { name: 'Register' }));
    await waitFor(() =>
      expect(registerUser).toHaveBeenCalledWith('Alice', 'alice@example.test', password)
    );
    // Drain the 1.5s redirect timer so it cannot fire during a later test.
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/login'), { timeout: 3000 });
  });

  it('rejects a password that has no special character', async () => {
    const user = userEvent.setup();
    renderWithRouter(<Register />);
    await user.type(screen.getByPlaceholderText('Full Name'), 'Alice');
    await user.type(screen.getByPlaceholderText('Email'), 'alice@example.test');
    await user.type(screen.getByPlaceholderText('Password'), 'Password123');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByText(/special char/i)).toBeInTheDocument();
    expect(registerUser).not.toHaveBeenCalled();
  });

  it('shows API error message on registration failure', async () => {
    registerUser.mockRejectedValue({ response: { data: 'Email already exists.' } });
    const user = userEvent.setup();
    renderWithRouter(<Register />);
    await user.type(screen.getByPlaceholderText('Full Name'), 'Bob');
    await user.type(screen.getByPlaceholderText('Email'), 'bob@example.test');
    await user.type(screen.getByPlaceholderText('Password'), 'Password@123');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByText('Email already exists.')).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });
});
