import React from 'react';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AdminDashboard from './AdminDashboard';
import { renderWithRouter } from '../test/test-utils';

const navigate = vi.fn();

// Stub all child components to avoid deep dependency chains
vi.mock('../components/ProductList.jsx', () => ({ default: () => <div>ProductList</div> }));
vi.mock('../components/ProductForm.jsx', () => ({ default: () => <div>ProductForm</div> }));
vi.mock('../components/OrderList.jsx', () => ({ default: () => <div>OrderList</div> }));
vi.mock('../components/CreateOrder.jsx', () => ({ default: () => <div>CreateOrder</div> }));
vi.mock('../components/OrderDetails.jsx', () => ({ default: () => <div>OrderDetails</div> }));
vi.mock('../components/Analytics.jsx', () => ({ default: () => <div>Analytics</div> }));
vi.mock('../components/Insights.jsx', () => ({ default: () => <div>Insights</div> }));
vi.mock('../components/Profile.jsx', () => ({ default: () => <div>Profile</div> }));
vi.mock('../components/UserList.jsx', () => ({ default: () => <div>UserList</div> }));
vi.mock('../components/UserDetails.jsx', () => ({ default: () => <div>UserDetails</div> }));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

describe('AdminDashboard logout', () => {
  beforeEach(() => {
    navigate.mockReset();
    localStorage.setItem('token', 'test-token');
    localStorage.setItem('role', 'ADMIN');
    localStorage.setItem('userId', '1');
    localStorage.setItem('userName', 'Admin');
    localStorage.setItem('userEmail', 'admin@example.test');
  });

  it('clears localStorage and navigates to /login on logout', async () => {
    const user = userEvent.setup();
    renderWithRouter(<AdminDashboard />, { route: '/admin/products' });
    // Open profile dropdown
    await user.click(screen.getByRole('button', { name: /Admin/i }));
    // Click logout
    await user.click(screen.getByRole('button', { name: /Logout/i }));
    expect(localStorage.getItem('token')).toBeNull();
    expect(localStorage.getItem('role')).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/login');
  });
});
