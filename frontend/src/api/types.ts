export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data: T;
  timestamp?: string;
}

export interface Product {
  id: string;
  productCode: string;
  name: string;
  description: string;
  pricePaise: number;
  currency: string;
  active: boolean;
  createdAt?: string;
}

export interface CreateOrderRequest {
  productCode: string;
  customerName?: string;
  customerEmail?: string;
  customerPhone?: string;
  notes?: string;
}

export interface OrderResponse {
  id: string;
  orderReference: string;
  productId: string;
  productName: string;
  amountPaise: number;
  currency: string;
  status: 'PENDING' | 'PAYMENT_INITIATED' | 'PAYMENT_CAPTURED' | 'PAYMENT_FAILED' | 'CANCELLED';
  razorpayOrderId?: string;
  razorpayKeyId?: string;
  customerName?: string;
  customerEmail?: string;
  createdAt: string;
  expiresAt?: string;
}

export interface PaymentItem {
  id: string;
  businessOrderId: string;
  razorpayPaymentId: string;
  razorpayOrderId: string;
  amountPaise: number;
  currency: string;
  status: 'AUTHORIZED' | 'CAPTURED' | 'FAILED' | 'REFUNDED' | 'DISPUTED';
  method?: string;
  bank?: string;
  wallet?: string;
  vpa?: string;
  email?: string;
  contact?: string;
  feePaise?: number;
  taxPaise?: number;
  international?: boolean;
  signatureVerified: boolean;
  createdAt: string;
}

export interface RefundItem {
  id: string;
  paymentId: string;
  businessOrderId: string;
  razorpayRefundId: string;
  amountPaise: number;
  currency: string;
  status: 'PENDING' | 'PROCESSED' | 'FAILED';
  reason?: string;
  notes?: string;
  createdAt: string;
}

export interface SettlementItem {
  id: string;
  razorpaySettlementId: string;
  amountPaise: number;
  feesPaise: number;
  taxPaise: number;
  status: 'PENDING' | 'PROCESSED' | 'FAILED';
  utr?: string;
  settledAt?: string;
  createdAt: string;
}

export interface RiskFlagItem {
  id: string;
  entityType: string;
  entityId: string;
  ruleCode: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  description: string;
  status: 'OPEN' | 'REVIEWED' | 'DISMISSED';
  reviewNotes?: string;
  createdAt: string;
}

export interface LedgerAccountItem {
  id: string;
  code: string;
  name: string;
  accountType: 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE';
  description?: string;
  balancePaise?: number;
}

export interface LedgerEntryItem {
  id: string;
  entryType: 'DEBIT' | 'CREDIT';
  amountPaise: number;
  currency: string;
  description: string;
  ledgerAccount: LedgerAccountItem;
}

export interface LedgerTransactionItem {
  id: string;
  sourceType: string;
  sourceId: string;
  description: string;
  idempotencyKey: string;
  amountPaise: number;
  currency: string;
  postedAt: string;
  entries: LedgerEntryItem[];
}

export interface DashboardStats {
  totalRevenuePaise: number;
  totalRevenueRupees: number;
  successfulPaymentsCount: number;
  failedPaymentsCount: number;
  totalOrdersCount: number;
  pendingOrdersCount: number;
  refundedAmountPaise: number;
  refundsCount: number;
  settledAmountPaise: number;
  openRiskFlagsCount: number;
  webhookEventsCount: number;
  ledgerTransactionsCount: number;
}
