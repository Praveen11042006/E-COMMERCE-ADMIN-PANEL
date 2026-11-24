import React from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import OrderDetails from './OrderDetails';
import { renderWithRouter } from '../test/test-utils';

const getOrder = vi.fn();
const updateOrderStatus = vi.fn();
const deleteOrder = vi.fn();
const fetchPaymentsForOrder = vi.fn();
const payOrder = vi.fn();
const fetchInvoice = vi.fn();
const navigate = vi.fn();

vi.mock('../utils/api', () => ({
  getOrder: (...args) => getOrder(...args),
  updateOrderStatus: (...args) => updateOrderStatus(...args),
  deleteOrder: (...args) => deleteOrder(...args),
  fetchPaymentsForOrder: (...args) => fetchPaymentsForOrder(...args),
  payOrder: (...args) => payOrder(...args),
  fetchInvoice: (...args) => fetchInvoice(...args),
}));
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => navigate, useParams: () => ({ id: '10' }) };
});

const sampleOrder = {
  id: 10,
  customerName: 'Carol Smith',
  customerEmail: 'carol@example.test',
  shippingAddress: '3 Test Rd',
  orderDate: '2024-03-01T12:00:00',
  status: 'PENDING',
  totalAmount: 500,
  discountAmount: 0,
  orderItems: [{ id: 1, product: { id: 1, name: 'Widget' }, quantity: 2, priceAtPurchase: 250 }],
};

const successfulPayment = {
  id: 5,
  orderId: 10,
  amount: 500,
  method: 'MOCK',
  status: 'SUCCESS',
  transactionRef: 'MOCK-ABCD1234',
  invoiceNumber: 'INV-2026-00010',
  createdAt: '2024-03-01T13:00:00',
};

describe('OrderDetails', () => {
  beforeEach(() => {
    getOrder.mockReset();
    updateOrderStatus.mockReset();
    deleteOrder.mockReset();
    fetchPaymentsForOrder.mockReset();
    payOrder.mockReset();
    fetchInvoice.mockReset();
    navigate.mockReset();

    getOrder.mockResolvedValue({ data: sampleOrder });
    fetchPaymentsForOrder.mockResolvedValue({ data: [] });
  });

  it('renders order customer name, id and status after load', async () => {
    renderWithRouter(<OrderDetails />);
    expect(await screen.findByText('Carol Smith')).toBeInTheDocument();
    expect(screen.getAllByText('#10').length).toBeGreaterThan(0);
    expect(screen.getAllByText('PENDING').length).toBeGreaterThan(0);
  });

  it('shows error state when the API fails', async () => {
    getOrder.mockRejectedValue(new Error('network'));
    renderWithRouter(<OrderDetails />);
    expect(await screen.findByText(/Failed to load order details/i)).toBeInTheDocument();
  });

  it('calls updateOrderStatus and shows success message on status change', async () => {
    updateOrderStatus.mockResolvedValue({});
    const user = userEvent.setup();
    renderWithRouter(<OrderDetails />);
    await screen.findByText('Carol Smith');
    const select = screen.getByRole('combobox');
    await user.selectOptions(select, 'SHIPPED');
    await user.click(screen.getByRole('button', { name: /Update Status/i }));
    await waitFor(() => expect(updateOrderStatus).toHaveBeenCalledWith('10', 'SHIPPED'));
    expect(await screen.findByText(/updated to SHIPPED/i)).toBeInTheDocument();
  });

  it('shows the stored payment record instead of non-existent order fields', async () => {
    fetchPaymentsForOrder.mockResolvedValue({ data: [successfulPayment] });
    renderWithRouter(<OrderDetails />);

    expect(await screen.findByText('INV-2026-00010')).toBeInTheDocument();
    expect(screen.getByText('MOCK-ABCD1234')).toBeInTheDocument();
    expect(screen.getByText('This order has a successful (mock) payment.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Pay now/i })).not.toBeInTheDocument();
  });

  it('records a mock payment for an unpaid order', async () => {
    payOrder.mockResolvedValue({ data: successfulPayment });
    fetchPaymentsForOrder
      .mockResolvedValueOnce({ data: [] })
      .mockResolvedValueOnce({ data: [successfulPayment] });
    const user = userEvent.setup();
    renderWithRouter(<OrderDetails />);

    await screen.findByText('Carol Smith');
    await user.click(await screen.findByRole('button', { name: /Pay now \(mock\)/i }));

    await waitFor(() => expect(payOrder).toHaveBeenCalledWith(10));
    expect(await screen.findByText('Mock payment recorded for this order')).toBeInTheDocument();
    expect(await screen.findByText('INV-2026-00010')).toBeInTheDocument();
  });

  it('opens the server-generated invoice with its real totals', async () => {
    fetchInvoice.mockResolvedValue({
      data: {
        invoiceNumber: 'INV-2026-00010',
        orderId: 10,
        customer: 'Carol Smith',
        customerEmail: 'carol@example.test',
        status: 'PENDING',
        currency: 'INR',
        items: [{ product: 'Widget', quantity: 2, unitPrice: 250, lineTotal: 500 }],
        subtotal: 500,
        discount: 50,
        couponCode: 'SAVE10',
        total: 450,
      },
    });
    const user = userEvent.setup();
    renderWithRouter(<OrderDetails />);

    await screen.findByText('Carol Smith');
    await user.click(screen.getByRole('button', { name: /View invoice/i }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('Discount (SAVE10)')).toBeInTheDocument();
    expect(screen.getByText('₹450.00')).toBeInTheDocument();
    expect(screen.getByText('Total (INR)')).toBeInTheDocument();
  });

  it('does not offer payment for a cancelled order', async () => {
    getOrder.mockResolvedValue({ data: { ...sampleOrder, status: 'CANCELLED' } });
    renderWithRouter(<OrderDetails />);

    await screen.findByText('Carol Smith');
    expect(screen.queryByRole('button', { name: /Pay now/i })).not.toBeInTheDocument();
  });

  it('shows a payment error message when the mock payment fails', async () => {
    payOrder.mockRejectedValue({
      response: { data: { message: 'Payment already succeeded for this order' } },
    });
    const user = userEvent.setup();
    renderWithRouter(<OrderDetails />);

    await screen.findByText('Carol Smith');
    await user.click(await screen.findByRole('button', { name: /Pay now \(mock\)/i }));

    expect(await screen.findByText('Payment already succeeded for this order')).toBeInTheDocument();
  });
});
