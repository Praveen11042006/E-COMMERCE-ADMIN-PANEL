import React from 'react';
import { screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import PrivateRoute from './PrivateRoute';
import { renderWithRouter } from '../test/test-utils';

describe('PrivateRoute', () => {
  it('redirects an anonymous visitor to login', () => {
    renderWithRouter(<PrivateRoute role="ADMIN"><div>Protected</div></PrivateRoute>, { route: '/admin/products' });
    expect(screen.queryByText('Protected')).not.toBeInTheDocument();
  });

  it('renders content for the matching authenticated role', () => {
    localStorage.setItem('token', 'test-token');
    localStorage.setItem('role', 'ADMIN');
    renderWithRouter(<PrivateRoute role="ADMIN"><div>Protected</div></PrivateRoute>);
    expect(screen.getByText('Protected')).toBeInTheDocument();
  });
});
