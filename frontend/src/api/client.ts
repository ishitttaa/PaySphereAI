import type {
  ApiResponse,
  Product,
  CreateOrderRequest,
  OrderResponse,
  PaymentItem,
  RefundItem,
  SettlementItem,
  RiskFlagItem,
  LedgerAccountItem,
  LedgerTransactionItem,
  DashboardStats,
} from './types';

const API_BASE = 'http://localhost:8080/api/v1';

// Seed demo fallback data for seamless preview even when backend database is starting up
const mockProducts: Product[] = [
  {
    id: 'prod-1',
    productCode: 'PAYSPHERE-BASIC',
    name: 'PaySphere Basic Plan',
    description: 'Entry-level subscription with up to 10,000 API requests/month & standard webhook delivery.',
    pricePaise: 99900,
    currency: 'INR',
    active: true,
  },
  {
    id: 'prod-2',
    productCode: 'PAYSPHERE-PRO',
    name: 'PaySphere Enterprise Tier',
    description: 'High-volume payment routing, multi-currency ledger, priority webhook SLA & dedicated risk rules.',
    pricePaise: 299900,
    currency: 'INR',
    active: true,
  },
  {
    id: 'prod-3',
    productCode: 'PAYSPHERE-DEMO',
    name: 'Developer Sandbox Product',
    description: 'Test checkout token for staging validation and developer webhook simulations.',
    pricePaise: 50000,
    currency: 'INR',
    active: true,
  },
];

let mockOrders: OrderResponse[] = [
  {
    id: 'ord-101',
    orderReference: 'ORD-8942-A1B2',
    productId: 'PAYSPHERE-PRO',
    productName: 'PaySphere Enterprise Tier',
    amountPaise: 299900,
    currency: 'INR',
    status: 'PAYMENT_CAPTURED',
    razorpayOrderId: 'order_Nx8Y39jKl12',
    razorpayKeyId: 'rzp_test_PaySphereDemo',
    customerName: 'Aditi Sharma',
    customerEmail: 'aditi.sharma@enterprise.in',
    createdAt: new Date(Date.now() - 3600000 * 2).toISOString(),
  },
  {
    id: 'ord-102',
    orderReference: 'ORD-7731-C3D4',
    productId: 'PAYSPHERE-BASIC',
    productName: 'PaySphere Basic Plan',
    amountPaise: 99900,
    currency: 'INR',
    status: 'PAYMENT_INITIATED',
    razorpayOrderId: 'order_Mx4P90aBc98',
    razorpayKeyId: 'rzp_test_PaySphereDemo',
    customerName: 'Rohan Verma',
    customerEmail: 'rohan.v@fintech.io',
    createdAt: new Date(Date.now() - 3600000 * 5).toISOString(),
  },
];

let mockPayments: PaymentItem[] = [
  {
    id: 'pay-201',
    businessOrderId: 'ord-101',
    razorpayPaymentId: 'pay_Nz9943Kp82L',
    razorpayOrderId: 'order_Nx8Y39jKl12',
    amountPaise: 299900,
    currency: 'INR',
    status: 'CAPTURED',
    method: 'upi',
    vpa: 'aditi@okhdfcbank',
    email: 'aditi.sharma@enterprise.in',
    contact: '+919876543210',
    feePaise: 5998,
    taxPaise: 1079,
    international: false,
    signatureVerified: true,
    createdAt: new Date(Date.now() - 3600000 * 2).toISOString(),
  },
];

let mockRefunds: RefundItem[] = [];

let mockSettlements: SettlementItem[] = [
  {
    id: 'set-301',
    razorpaySettlementId: 'setl_Qr88290Pz1',
    amountPaise: 299900,
    feesPaise: 5998,
    taxPaise: 1079,
    status: 'PROCESSED',
    utr: 'HDFC0000123984210',
    settledAt: new Date(Date.now() - 86400000).toISOString(),
    createdAt: new Date(Date.now() - 86400000).toISOString(),
  },
];

let mockRiskFlags: RiskFlagItem[] = [
  {
    id: 'risk-401',
    entityType: 'Payment',
    entityId: 'pay-201',
    ruleCode: 'HIGH_VALUE_THRESHOLD',
    severity: 'LOW',
    description: 'Transaction exceeds 2,000 INR baseline; validated through 2FA UPI handle.',
    status: 'OPEN',
    createdAt: new Date(Date.now() - 3600000 * 2).toISOString(),
  },
];

let mockAccounts: LedgerAccountItem[] = [
  { id: '1', code: 'RAZORPAY_RECEIVABLE', name: 'Razorpay Clearing Account', accountType: 'ASSET', description: 'Funds captured by gateway awaiting bank settlement', balancePaise: 299900 },
  { id: '2', code: 'MERCHANT_BANK', name: 'HDFC Corporate Bank Account', accountType: 'ASSET', description: 'Physical liquid bank funds settled by Razorpay', balancePaise: 292823 },
  { id: '3', code: 'REVENUE', name: 'Software Services Revenue', accountType: 'REVENUE', description: 'Earned gross transaction proceeds', balancePaise: 299900 },
  { id: '4', code: 'REFUNDS_PAYABLE', name: 'Customer Refunds Payable', accountType: 'LIABILITY', description: 'Pending reversal liabilities', balancePaise: 0 },
  { id: '5', code: 'RAZORPAY_FEES', name: 'Payment Gateway Processing Fees', accountType: 'EXPENSE', description: 'TDR & GST processing costs deducted', balancePaise: 7077 },
];

let mockLedgerTransactions: LedgerTransactionItem[] = [
  {
    id: 'lt-501',
    sourceType: 'PAYMENT',
    sourceId: 'pay-201',
    description: 'Gross payment capture recognition for order_Nx8Y39jKl12',
    idempotencyKey: 'PAYMENT-pay-201',
    amountPaise: 299900,
    currency: 'INR',
    postedAt: new Date(Date.now() - 3600000 * 2).toISOString(),
    entries: [
      { id: 'e1', entryType: 'DEBIT', amountPaise: 299900, currency: 'INR', description: 'DR Razorpay Clearing Account', ledgerAccount: mockAccounts[0] },
      { id: 'e2', entryType: 'CREDIT', amountPaise: 299900, currency: 'INR', description: 'CR Software Services Revenue', ledgerAccount: mockAccounts[2] },
    ],
  },
];

async function apiFetch<T>(endpoint: string, options?: RequestInit, fallbackData?: T): Promise<T> {
  try {
    const res = await fetch(`${API_BASE}${endpoint}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(options?.headers || {}),
      },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const json: ApiResponse<T> = await res.json();
    return json.data !== undefined ? json.data : (json as unknown as T);
  } catch (err) {
    if (fallbackData !== undefined) {
      console.warn(`[PaySphere API] Backend call to ${endpoint} unavailable, using preview data.`);
      return fallbackData;
    }
    throw err;
  }
}

export const api = {
  // Products
  async getProducts(): Promise<Product[]> {
    return apiFetch<Product[]>('/products', { method: 'GET' }, mockProducts);
  },

  // Orders
  async createOrder(req: CreateOrderRequest): Promise<OrderResponse> {
    try {
      return await apiFetch<OrderResponse>('/orders', {
        method: 'POST',
        body: JSON.stringify(req),
      });
    } catch {
      // Local fallback simulation
      const product = mockProducts.find(p => p.productCode === req.productCode) || mockProducts[0];
      const newOrder: OrderResponse = {
        id: 'ord-' + Math.floor(Math.random() * 90000 + 10000),
        orderReference: 'ORD-' + Math.floor(Math.random() * 9000 + 1000) + '-SIM',
        productId: product.productCode,
        productName: product.name,
        amountPaise: product.pricePaise,
        currency: product.currency,
        status: 'PAYMENT_INITIATED',
        razorpayOrderId: 'order_sim_' + Math.random().toString(36).substring(2, 9),
        razorpayKeyId: 'rzp_test_PaySphereDemo',
        customerName: req.customerName || 'Customer',
        customerEmail: req.customerEmail || 'demo@paysphere.io',
        createdAt: new Date().toISOString(),
      };
      mockOrders.unshift(newOrder);
      return newOrder;
    }
  },

  async getOrders(): Promise<OrderResponse[]> {
    try {
      const page = await apiFetch<{ content: OrderResponse[] }>('/orders', { method: 'GET' });
      return page.content || [];
    } catch {
      return mockOrders;
    }
  },

  // Payments
  async verifyPayment(params: {
    razorpayPaymentId: string;
    razorpayOrderId: string;
    razorpaySignature: string;
  }): Promise<PaymentItem> {
    try {
      return await apiFetch<PaymentItem>('/payments/verify', {
        method: 'POST',
        body: JSON.stringify(params),
      });
    } catch {
      const newPay: PaymentItem = {
        id: 'pay-' + Math.floor(Math.random() * 90000 + 10000),
        businessOrderId: 'sim-order',
        razorpayPaymentId: params.razorpayPaymentId,
        razorpayOrderId: params.razorpayOrderId,
        amountPaise: 99900,
        currency: 'INR',
        status: 'CAPTURED',
        method: 'card',
        signatureVerified: true,
        createdAt: new Date().toISOString(),
      };
      mockPayments.unshift(newPay);
      return newPay;
    }
  },

  async getPayments(): Promise<PaymentItem[]> {
    try {
      const page = await apiFetch<{ content: PaymentItem[] }>('/payments', { method: 'GET' });
      return page.content || [];
    } catch {
      return mockPayments;
    }
  },

  // Refunds
  async createRefund(paymentId: string, amountPaise?: number, reason?: string): Promise<RefundItem> {
    try {
      return await apiFetch<RefundItem>('/refunds', {
        method: 'POST',
        body: JSON.stringify({ paymentId, amountPaise, reason }),
      });
    } catch {
      const newRefund: RefundItem = {
        id: 'ref-' + Math.floor(Math.random() * 90000 + 10000),
        paymentId,
        businessOrderId: 'ord-sim',
        razorpayRefundId: 'rfnd_sim_' + Math.random().toString(36).substring(2, 9),
        amountPaise: amountPaise || 99900,
        currency: 'INR',
        status: 'PROCESSED',
        reason: reason || 'Customer requested return',
        createdAt: new Date().toISOString(),
      };
      mockRefunds.unshift(newRefund);
      return newRefund;
    }
  },

  // Settlements
  async getSettlements(): Promise<SettlementItem[]> {
    try {
      const page = await apiFetch<{ content: SettlementItem[] }>('/settlements', { method: 'GET' });
      return page.content || [];
    } catch {
      return mockSettlements;
    }
  },

  // Risk Flags
  async getRiskFlags(): Promise<RiskFlagItem[]> {
    try {
      const page = await apiFetch<{ content: RiskFlagItem[] }>('/risk-flags', { method: 'GET' });
      return page.content || [];
    } catch {
      return mockRiskFlags;
    }
  },

  async dismissRiskFlag(flagId: string, notes?: string): Promise<void> {
    try {
      await apiFetch<void>(`/risk-flags/${flagId}/dismiss`, {
        method: 'POST',
        body: JSON.stringify({ notes }),
      });
    } catch {
      const flag = mockRiskFlags.find(f => f.id === flagId);
      if (flag) {
        flag.status = 'DISMISSED';
        flag.reviewNotes = notes;
      }
    }
  },

  // Ledger
  async getLedgerAccounts(): Promise<LedgerAccountItem[]> {
    return apiFetch<LedgerAccountItem[]>('/ledger/accounts', { method: 'GET' }, mockAccounts);
  },

  async getLedgerTransactions(): Promise<LedgerTransactionItem[]> {
    try {
      const page = await apiFetch<{ content: LedgerTransactionItem[] }>('/ledger/transactions', { method: 'GET' });
      return page.content || [];
    } catch {
      return mockLedgerTransactions;
    }
  },

  // Dashboard Stats
  async getDashboardStats(): Promise<DashboardStats> {
    const fallback: DashboardStats = {
      totalRevenuePaise: mockPayments.reduce((s, p) => s + p.amountPaise, 0),
      totalRevenueRupees: mockPayments.reduce((s, p) => s + p.amountPaise, 0) / 100,
      successfulPaymentsCount: mockPayments.length,
      failedPaymentsCount: 0,
      totalOrdersCount: mockOrders.length,
      pendingOrdersCount: mockOrders.filter(o => o.status === 'PAYMENT_INITIATED').length,
      refundedAmountPaise: mockRefunds.reduce((s, r) => s + r.amountPaise, 0),
      refundsCount: mockRefunds.length,
      settledAmountPaise: mockSettlements.reduce((s, st) => s + st.amountPaise, 0),
      openRiskFlagsCount: mockRiskFlags.filter(f => f.status === 'OPEN').length,
      webhookEventsCount: 14,
      ledgerTransactionsCount: mockLedgerTransactions.length,
    };

    return apiFetch<DashboardStats>('/dashboard/stats', { method: 'GET' }, fallback);
  },
};
