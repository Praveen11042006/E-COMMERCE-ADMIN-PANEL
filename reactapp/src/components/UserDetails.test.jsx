import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import UserDetails from './UserDetails';
import { renderWithRouter } from '../test/test-utils';

const getUserById = vi.fn();
const updateUser = vi.fn();
const deleteUser = vi.fn();
const navigate = vi.fn();

vi.mock('../utils/api.js', () => ({
  getUserById: (...args) => getUserById(...args),
  updateUser: (...args) => updateUser(...args),
  deleteUser: (...args) => deleteUser(...args),
}));
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate, useParams: () => ({ id: '5' }) };
});

const sampleUser = { id: 5, name: 'Dave Jones', email: 'dave@example.test', active: true };

describe('UserDetails', () => {
  beforeEach(() => {
    getUserById.mockReset();
    updateUser.mockReset();
    deleteUser.mockReset();
    navigate.mockReset();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  it('renders user name and email after load', async () => {
    getUserById.mockResolvedValue({ data: sampleUser });
    renderWithRouter(<UserDetails />);
    expect(await screen.findByDisplayValue('Dave Jones')).toBeInTheDocument();
    expect(screen.getByDisplayValue('dave@example.test')).toBeInTheDocument();
  });

  it('calls updateUser with active=false when deactivated and saved', async () => {
    getUserById.mockResolvedValue({ data: sampleUser });
    updateUser.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithRouter(<UserDetails />);
    await screen.findByDisplayValue('Dave Jones');
    const activeCheckbox = screen.getByRole('checkbox');
    await user.click(activeCheckbox); // uncheck → deactivate
    await user.click(screen.getByRole('button', { name: /Save Changes/i }));
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith('5', expect.objectContaining({ active: false }))
    );
    expect(navigate).toHaveBeenCalledWith('/admin/users');
  });

  it('calls deleteUser and navigates away on confirmed delete', async () => {
    getUserById.mockResolvedValue({ data: sampleUser });
    deleteUser.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithRouter(<UserDetails />);
    await screen.findByDisplayValue('Dave Jones');
    await user.click(screen.getByRole('button', { name: /Delete User/i }));
    await waitFor(() => expect(deleteUser).toHaveBeenCalledWith('5'));
    expect(navigate).toHaveBeenCalledWith('/admin/users');
  });
});
