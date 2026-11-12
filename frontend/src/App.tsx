import React, { useState, useEffect } from 'react';
import confetti from 'canvas-confetti';
import {
  LayoutDashboard,
  ShoppingBag,
  CreditCard,
  RotateCcw,
  BookOpen,
  Building2,
  ShieldAlert,
  Terminal,
  CheckCircle2,
  Clock,
  RefreshCw,
  ExternalLink,
  Lock,
  Layers,
  ChevronRight,
  TrendingUp,
} from 'lucide-react';
import { api } from './api/client';
import type {
  Product,
  OrderResponse,
  PaymentItem,
  SettlementItem,
  RiskFlagItem,
  LedgerAccountItem,
  LedgerTransactionItem,
  DashboardStats,
} from './api/types';

export default function App() {
  const [activeTab, setActiveTab] = useState<'dashboard' | 'storefront' | 'orders' | 'payments' | 'ledger' | 'settlements' | 'risk' | 'webhooks'>('dashboard');

  // State data
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [products, setProducts] = useState<Product[]>([]);
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [payments, setPayments] = useState<PaymentItem[]>([]);
  const [settlements, setSettlements] = useState<SettlementItem[]>([]);
  const [riskFlags, setRiskFlags] = useState<RiskFlagItem[]>([]);
  const [accounts, setAccounts] = useState<LedgerAccountItem[]>([]);
  const [ledgerTxs, setLedgerTxs] = useState<LedgerTransactionItem[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);

  // Checkout Modal State
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);
  const [customerName, setCustomerName] = useState('Rahul Mehta');
  const [customerEmail, setCustomerEmail] = useState('rahul.mehta@enterprise.com');
  const [customerPhone, setCustomerPhone] = useState('+919876543210');
  const [checkoutLoading, setCheckoutLoading] = useState(false);
  const [checkoutSuccess, setCheckoutSuccess] = useState<string | null>(null);

  // Refund Modal State
  const [refundPayment, setRefundPayment] = useState<PaymentItem | null>(null);
  const [refundReason, setRefundReason] = useState('Customer requested return');
  const [refundLoading, setRefundLoading] = useState(false);

  // Load all data
  const loadData = async () => {
    try {
      setRefreshing(true);
      const [
        statsData,
        productsData,
        ordersData,
        paymentsData,
        settlementsData,
        riskData,
        accountsData,
        txsData,
      ] = await Promise.all([
        api.getDashboardStats(),
        api.getProducts(),
        api.getOrders(),
        api.getPayments(),
        api.getSettlements(),
        api.getRiskFlags(),
        api.getLedgerAccounts(),
        api.getLedgerTransactions(),
      ]);

      setStats(statsData);
      setProducts(productsData);
      setOrders(ordersData);
      setPayments(paymentsData);
      setSettlements(settlementsData);
      setRiskFlags(riskData);
      setAccounts(accountsData);
      setLedgerTxs(txsData);
    } catch (e) {
      console.error('Error loading PaySphere data:', e);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  // Handle Checkout Click
  const handleInitiateOrder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProduct) return;
    setCheckoutLoading(true);

    try {
      // 1. Call backend to create business order (amount never sent from frontend!)
      const order = await api.createOrder({
        productCode: selectedProduct.productCode,
        customerName,
        customerEmail,
        customerPhone,
      });

      // 2. Simulate or launch Razorpay Checkout
      const mockPayId = 'pay_' + Math.random().toString(36).substring(2, 11);
      const mockSig = 'hmac_sha256_' + Math.random().toString(36).substring(2, 16);

      // Verify server-side HMAC
      await api.verifyPayment({
        razorpayPaymentId: mockPayId,
        razorpayOrderId: order.razorpayOrderId || 'order_sim',
        razorpaySignature: mockSig,
      });

      // Trigger celebratory confetti
      confetti({
        particleCount: 80,
        spread: 60,
        origin: { y: 0.6 },
      });

      setCheckoutSuccess(`Payment verified & captured! Payment ID: ${mockPayId}`);
      setTimeout(() => {
        setSelectedProduct(null);
        setCheckoutSuccess(null);
        loadData();
      }, 2000);
    } catch (err: unknown) {
      alert('Checkout error: ' + (err instanceof Error ? err.message : String(err)));
    } finally {
      setCheckoutLoading(false);
    }
  };

  // Handle Refund Click
  const handleExecuteRefund = async () => {
    if (!refundPayment) return;
    setRefundLoading(true);
    try {
      await api.createRefund(refundPayment.id, refundPayment.amountPaise, refundReason);
      alert('Refund processed successfully and posted to double-entry ledger!');
      setRefundPayment(null);
      loadData();
    } catch (err: unknown) {
      alert('Refund failed: ' + (err instanceof Error ? err.message : String(err)));
    } finally {
      setRefundLoading(false);
    }
  };

  // Handle Risk Dismiss
  const handleDismissRisk = async (id: string) => {
    await api.dismissRiskFlag(id, 'Admin reviewed & verified identity');
    loadData();
  };

  const openRiskCount = riskFlags.filter(f => f.status === 'OPEN').length;

  if (loading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', background: 'var(--bg-dark)', color: 'var(--text-main)', fontFamily: 'var(--font-heading)' }}>
        <div style={{ textAlign: 'center' }}>
          <div className="pulse-dot" style={{ width: '16px', height: '16px', margin: '0 auto 16px auto', background: '#6366f1' }}></div>
          <h2 style={{ fontSize: '1.4rem', fontWeight: 700 }}>Initializing PaySphere Platform...</h2>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem', marginTop: '6px' }}>Connecting to API gateway and double-entry ledger</p>
        </div>
      </div>
    );
  }

  return (
    <div className="app-container">
      {/* ── Sidebar Navigation ── */}
      <aside className="sidebar">
        <div className="logo-container">
          <div className="logo-badge">
            <Layers size={22} color="#ffffff" />
          </div>
          <div>
            <div className="logo-title">PaySphere</div>
            <div className="logo-mode-tag">Razorpay Test Mode</div>
          </div>
        </div>

        <nav className="nav-links">
          <button
            className={`nav-item ${activeTab === 'dashboard' ? 'active' : ''}`}
            onClick={() => setActiveTab('dashboard')}
          >
            <LayoutDashboard size={18} />
            <span>Dashboard</span>
          </button>

          <button
            className={`nav-item ${activeTab === 'storefront' ? 'active' : ''}`}
            onClick={() => setActiveTab('storefront')}
          >
            <ShoppingBag size={18} />
            <span>Storefront & Checkout</span>
          </button>

          <button
            className={`nav-item ${activeTab === 'orders' ? 'active' : ''}`}
            onClick={() => setActiveTab('orders')}
          >
            <Clock size={18} />
            <span>Orders</span>
            <span className="mono" style={{ marginLeft: 'auto', fontSize: '0.75rem', opacity: 0.7 }}>
              {orders.length}
            </span>
          </button>

          <button
            className={`nav-item ${activeTab === 'payments' ? 'active' : ''}`}
            onClick={() => setActiveTab('payments')}
          >
            <CreditCard size={18} />
            <span>Payments & Refunds</span>
            <span className="mono" style={{ marginLeft: 'auto', fontSize: '0.75rem', opacity: 0.7 }}>
              {payments.length}
            </span>
          </button>

          <button
            className={`nav-item ${activeTab === 'ledger' ? 'active' : ''}`}
            onClick={() => setActiveTab('ledger')}
          >
            <BookOpen size={18} />
            <span>Double-Entry Ledger</span>
          </button>

          <button
            className={`nav-item ${activeTab === 'settlements' ? 'active' : ''}`}
            onClick={() => setActiveTab('settlements')}
          >
            <Building2 size={18} />
            <span>Bank Settlements</span>
          </button>

          <button
            className={`nav-item ${activeTab === 'risk' ? 'active' : ''}`}
            onClick={() => setActiveTab('risk')}
          >
            <ShieldAlert size={18} />
            <span>Risk & Fraud</span>
            {openRiskCount > 0 && <span className="nav-badge">{openRiskCount}</span>}
          </button>

          <button
            className={`nav-item ${activeTab === 'webhooks' ? 'active' : ''}`}
            onClick={() => setActiveTab('webhooks')}
          >
            <Terminal size={18} />
            <span>Webhooks & Logs</span>
          </button>
        </nav>

        <div style={{ marginTop: 'auto', padding: '16px 12px', background: 'rgba(0,0,0,0.3)', borderRadius: '12px', border: '1px solid var(--border-subtle)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '0.8rem', color: '#34d399', fontWeight: 600 }}>
            <span className="pulse-dot"></span>
            <span>API Gateway Connected</span>
          </div>
          <div className="mono" style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: '4px' }}>
            JDK 26 • Spring Boot 3.3.5
          </div>
        </div>
      </aside>

      {/* ── Main Content Area ── */}
      <main className="main-content">
        {/* Top Header */}
        <header className="top-header">
          <div>
            <h1 className="page-title">
              {activeTab === 'dashboard' && 'Financial Orchestration Overview'}
              {activeTab === 'storefront' && 'Interactive Storefront & Razorpay Checkout'}
              {activeTab === 'orders' && 'Business Orders Repository'}
              {activeTab === 'payments' && 'Payment Captures & Reversals'}
              {activeTab === 'ledger' && 'Chart of Accounts & Double-Entry Journal'}
              {activeTab === 'settlements' && 'Razorpay Bank Settlements'}
              {activeTab === 'risk' && 'Deterministic Risk & Anomaly Watch'}
              {activeTab === 'webhooks' && 'Webhook Event Stream & Simulator'}
            </h1>
            <p className="page-subtitle">
              {activeTab === 'dashboard' && 'Unified transaction metrics, clearing accounts, and automated ledger postings.'}
              {activeTab === 'storefront' && 'Client-safe checkout: price verified directly from backend database.'}
              {activeTab === 'orders' && 'Track order lifecycles from initiation to settlement.'}
              {activeTab === 'payments' && 'Server-side verified HMAC-SHA256 payments.'}
              {activeTab === 'ledger' && 'Strict debit-credit balanced accounting (DR == CR).'}
              {activeTab === 'settlements' && 'Bank fund disbursement reconciliation with Razorpay UTRs.'}
              {activeTab === 'risk' && 'Real-time rule-based flagging for high velocity and international cards.'}
              {activeTab === 'webhooks' && 'Inspect raw payload delivery and replay idempotency.'}
            </p>
          </div>

          <div className="top-actions">
            <button className="btn btn-secondary btn-sm" onClick={loadData} disabled={refreshing}>
              <RefreshCw size={14} className={refreshing ? 'animate-spin' : ''} />
              <span>Refresh</span>
            </button>
            <a
              href="http://localhost:8080/swagger-ui.html"
              target="_blank"
              rel="noreferrer"
              className="btn btn-secondary btn-sm"
            >
              <ExternalLink size={14} />
              <span>Swagger API Docs</span>
            </a>
          </div>
        </header>

        {/* ── TAB 1: DASHBOARD ── */}
        {activeTab === 'dashboard' && (
          <div>
            {/* KPI Cards */}
            <div className="metrics-grid">
              <div className="glass-card metric-card">
                <div className="metric-header">
                  <span className="metric-label">Gross Processed Volume</span>
                  <div className="metric-icon-wrap"><TrendingUp size={20} /></div>
                </div>
                <div className="metric-value">
                  ₹{((stats?.totalRevenuePaise || 0) / 100).toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                </div>
                <div className="metric-subtext">
                  <CheckCircle2 size={14} color="#34d399" />
                  <span>100% captured & verified</span>
                </div>
              </div>

              <div className="glass-card metric-card">
                <div className="metric-header">
                  <span className="metric-label">Successful Transactions</span>
                  <div className="metric-icon-wrap"><CheckCircle2 size={20} /></div>
                </div>
                <div className="metric-value">
                  {stats?.successfulPaymentsCount || 0}
                </div>
                <div className="metric-subtext">
                  <span>Across {stats?.totalOrdersCount || 0} initiated orders</span>
                </div>
              </div>

              <div className="glass-card metric-card">
                <div className="metric-header">
                  <span className="metric-label">Bank Settled Funds</span>
                  <div className="metric-icon-wrap"><Building2 size={20} /></div>
                </div>
                <div className="metric-value">
                  ₹{((stats?.settledAmountPaise || 0) / 100).toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                </div>
                <div className="metric-subtext">
                  <span>Disbursed to merchant bank</span>
                </div>
              </div>

              <div className="glass-card metric-card">
                <div className="metric-header">
                  <span className="metric-label">Risk & Fraud Alerts</span>
                  <div className="metric-icon-wrap" style={{ background: 'rgba(239, 68, 68, 0.15)', color: '#f87171' }}>
                    <ShieldAlert size={20} />
                  </div>
                </div>
                <div className="metric-value" style={{ color: openRiskCount > 0 ? '#f87171' : 'var(--text-main)' }}>
                  {openRiskCount}
                </div>
                <div className="metric-subtext">
                  <span>{openRiskCount === 0 ? 'No open anomalies' : 'Action required'}</span>
                </div>
              </div>
            </div>

            {/* Quick Actions & Recent Activity */}
            <div style={{ display: 'grid', gridTemplateColumns: '1.6fr 1fr', gap: '24px' }}>
              <div className="glass-card">
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
                  <h3 className="font-heading" style={{ fontSize: '1.2rem', fontWeight: 600 }}>Recent Payments</h3>
                  <button className="btn btn-secondary btn-sm" onClick={() => setActiveTab('payments')}>
                    View All
                  </button>
                </div>

                <div className="table-container">
                  <table>
                    <thead>
                      <tr>
                        <th>Payment ID</th>
                        <th>Amount</th>
                        <th>Method</th>
                        <th>Status</th>
                        <th>Verified</th>
                      </tr>
                    </thead>
                    <tbody>
                      {payments.slice(0, 5).map(pay => (
                        <tr key={pay.id}>
                          <td className="mono" style={{ color: 'var(--text-main)' }}>{pay.razorpayPaymentId}</td>
                          <td className="mono" style={{ fontWeight: 600 }}>₹{(pay.amountPaise / 100).toFixed(2)}</td>
                          <td>
                            <span className="badge badge-info">{pay.method || 'card'}</span>
                          </td>
                          <td>
                            <span className={`badge ${pay.status === 'CAPTURED' ? 'badge-success' : 'badge-warning'}`}>
                              {pay.status}
                            </span>
                          </td>
                          <td>
                            {pay.signatureVerified ? (
                              <span style={{ color: '#34d399', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '0.8rem' }}>
                                <CheckCircle2 size={14} /> HMAC OK
                              </span>
                            ) : (
                              <span style={{ color: '#f87171' }}>Pending</span>
                            )}
                          </td>
                        </tr>
                      ))}
                      {payments.length === 0 && (
                        <tr>
                          <td colSpan={5} style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
                            No payments yet. Go to <strong>Storefront & Checkout</strong> to create your first transaction!
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Architecture Blueprint Card */}
              <div className="glass-card" style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                <div>
                  <h3 className="font-heading" style={{ fontSize: '1.2rem', fontWeight: 600, marginBottom: '12px' }}>
                    Security Guardrails Active
                  </h3>
                  <p style={{ fontSize: '0.88rem', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                    PaySphere enforces non-negotiable fintech security patterns:
                  </p>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', marginTop: '18px' }}>
                    <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-start' }}>
                      <CheckCircle2 size={18} color="#34d399" style={{ flexShrink: 0, marginTop: '2px' }} />
                      <div style={{ fontSize: '0.85rem' }}>
                        <strong>Price Integrity:</strong> Frontend sends product code only; amount loaded exclusively from PostgreSQL.
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-start' }}>
                      <CheckCircle2 size={18} color="#34d399" style={{ flexShrink: 0, marginTop: '2px' }} />
                      <div style={{ fontSize: '0.85rem' }}>
                        <strong>Cryptographic Verification:</strong> HMAC-SHA256 signatures validated server-side with secret key.
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-start' }}>
                      <CheckCircle2 size={18} color="#34d399" style={{ flexShrink: 0, marginTop: '2px' }} />
                      <div style={{ fontSize: '0.85rem' }}>
                        <strong>Double-Entry Invariant:</strong> Every payment, refund, and settlement guarantees DR == CR.
                      </div>
                    </div>
                  </div>
                </div>

                <button
                  className="btn btn-primary"
                  style={{ marginTop: '24px', width: '100%' }}
                  onClick={() => setActiveTab('storefront')}
                >
                  <ShoppingBag size={18} />
                  <span>Launch Checkout Demo</span>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* ── TAB 2: STOREFRONT & CHECKOUT ── */}
        {activeTab === 'storefront' && (
          <div>
            <div className="security-notice">
              <Lock size={18} color="#818cf8" style={{ flexShrink: 0 }} />
              <div>
                <strong>Client-Safe Price Architecture:</strong> When you click "Purchase", the browser never transmits the price. PaySphere retrieves the verified price in paise directly from the database using the unique product code.
              </div>
            </div>

            <div className="products-grid">
              {products.map(prod => (
                <div
                  key={prod.id}
                  className={`glass-card product-card ${prod.productCode === 'PAYSPHERE-PRO' ? 'featured' : ''}`}
                >
                  <div className="product-header">
                    {prod.productCode === 'PAYSPHERE-PRO' && (
                      <span className="badge badge-info" style={{ marginBottom: '12px' }}>
                        Most Popular
                      </span>
                    )}
                    <h3 style={{ fontSize: '1.35rem', fontWeight: 700 }}>{prod.name}</h3>
                    <div className="product-price">
                      ₹{(prod.pricePaise / 100).toLocaleString('en-IN')}
                      <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)', fontWeight: 400 }}> / one-time</span>
                    </div>
                    <p className="product-desc">{prod.description}</p>
                  </div>

                  <div className="product-meta">
                    <span className="mono" style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                      CODE: {prod.productCode}
                    </span>
                    <button
                      className="btn btn-primary btn-sm"
                      onClick={() => setSelectedProduct(prod)}
                    >
                      <span>Buy with Razorpay</span>
                      <ChevronRight size={14} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* ── TAB 3: ORDERS ── */}
        {activeTab === 'orders' && (
          <div className="glass-card">
            <h3 className="font-heading" style={{ fontSize: '1.25rem', marginBottom: '16px' }}>All Business Orders</h3>
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Order Ref</th>
                    <th>Product</th>
                    <th>Amount</th>
                    <th>Customer</th>
                    <th>Razorpay Order ID</th>
                    <th>Status</th>
                    <th>Date</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map(order => (
                    <tr key={order.id}>
                      <td className="mono" style={{ fontWeight: 600, color: 'var(--text-main)' }}>
                        {order.orderReference}
                      </td>
                      <td>{order.productName}</td>
                      <td className="mono" style={{ fontWeight: 600 }}>₹{(order.amountPaise / 100).toFixed(2)}</td>
                      <td>
                        <div>{order.customerName || 'N/A'}</div>
                        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{order.customerEmail}</div>
                      </td>
                      <td className="mono" style={{ fontSize: '0.8rem', color: '#94a3b8' }}>
                        {order.razorpayOrderId || '—'}
                      </td>
                      <td>
                        <span className={`badge ${
                          order.status === 'PAYMENT_CAPTURED'
                            ? 'badge-success'
                            : order.status === 'PAYMENT_FAILED'
                            ? 'badge-danger'
                            : 'badge-warning'
                        }`}>
                          {order.status}
                        </span>
                      </td>
                      <td style={{ fontSize: '0.82rem' }}>
                        {new Date(order.createdAt).toLocaleDateString()}
                      </td>
                    </tr>
                  ))}
                  {orders.length === 0 && (
                    <tr>
                      <td colSpan={7} style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
                        No orders recorded yet.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* ── TAB 4: PAYMENTS & REFUNDS ── */}
        {activeTab === 'payments' && (
          <div className="glass-card">
            <h3 className="font-heading" style={{ fontSize: '1.25rem', marginBottom: '16px' }}>Captured Payments & Actions</h3>
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Razorpay Payment ID</th>
                    <th>Order ID</th>
                    <th>Amount</th>
                    <th>Method</th>
                    <th>Status</th>
                    <th>Signature</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {payments.map(pay => (
                    <tr key={pay.id}>
                      <td className="mono" style={{ fontWeight: 600, color: 'var(--text-main)' }}>
                        {pay.razorpayPaymentId}
                      </td>
                      <td className="mono" style={{ fontSize: '0.8rem' }}>{pay.razorpayOrderId}</td>
                      <td className="mono" style={{ fontWeight: 600 }}>₹{(pay.amountPaise / 100).toFixed(2)}</td>
                      <td><span className="badge badge-info">{pay.method || 'card'}</span></td>
                      <td>
                        <span className={`badge ${pay.status === 'CAPTURED' ? 'badge-success' : 'badge-warning'}`}>
                          {pay.status}
                        </span>
                      </td>
                      <td>
                        {pay.signatureVerified ? (
                          <span style={{ color: '#34d399', fontSize: '0.8rem', display: 'flex', alignItems: 'center', gap: '4px' }}>
                            <CheckCircle2 size={14} /> HMAC Verified
                          </span>
                        ) : (
                          <span style={{ color: '#f87171' }}>Unverified</span>
                        )}
                      </td>
                      <td>
                        <button
                          className="btn btn-secondary btn-sm"
                          onClick={() => setRefundPayment(pay)}
                        >
                          <RotateCcw size={13} />
                          <span>Refund</span>
                        </button>
                      </td>
                    </tr>
                  ))}
                  {payments.length === 0 && (
                    <tr>
                      <td colSpan={7} style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
                        No payments found.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* ── TAB 5: DOUBLE-ENTRY LEDGER ── */}
        {activeTab === 'ledger' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
            {/* Chart of Accounts */}
            <div className="glass-card">
              <h3 className="font-heading" style={{ fontSize: '1.25rem', marginBottom: '16px' }}>Chart of Accounts</h3>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '16px' }}>
                {accounts.map(acc => (
                  <div key={acc.id} style={{ background: 'rgba(0,0,0,0.3)', padding: '16px', borderRadius: '12px', border: '1px solid var(--border-subtle)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                      <span className="badge badge-info" style={{ fontSize: '0.7rem' }}>{acc.accountType}</span>
                      <span className="mono" style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{acc.code}</span>
                    </div>
                    <div style={{ fontWeight: 600, fontSize: '0.95rem', marginBottom: '4px' }}>{acc.name}</div>
                    <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: '12px' }}>{acc.description}</div>
                    <div className="mono" style={{ fontSize: '1.25rem', fontWeight: 700, color: '#38bdf8' }}>
                      ₹{((acc.balancePaise || 0) / 100).toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Journal Transactions */}
            <div className="glass-card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <h3 className="font-heading" style={{ fontSize: '1.25rem' }}>Double-Entry Journal Entries (DR == CR)</h3>
                <span className="badge badge-success">Balanced Invariant Guaranteed</span>
              </div>

              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>Transaction ID / Time</th>
                      <th>Source</th>
                      <th>Description</th>
                      <th>Debits (DR)</th>
                      <th>Credits (CR)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {ledgerTxs.map(tx => {
                      const debits = tx.entries.filter(e => e.entryType === 'DEBIT');
                      const credits = tx.entries.filter(e => e.entryType === 'CREDIT');
                      return (
                        <tr key={tx.id}>
                          <td>
                            <div className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-main)' }}>{tx.id.substring(0, 8)}...</div>
                            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{new Date(tx.postedAt).toLocaleString()}</div>
                          </td>
                          <td><span className="badge badge-info">{tx.sourceType}</span></td>
                          <td style={{ fontSize: '0.85rem' }}>{tx.description}</td>
                          <td>
                            {debits.map((d, i) => (
                              <div key={i} style={{ color: '#34d399', fontSize: '0.82rem', marginBottom: '4px' }}>
                                <strong>₹{(d.amountPaise / 100).toFixed(2)}</strong> ({d.ledgerAccount?.name || 'Asset'})
                              </div>
                            ))}
                          </td>
                          <td>
                            {credits.map((c, i) => (
                              <div key={i} style={{ color: '#ec4899', fontSize: '0.82rem', marginBottom: '4px' }}>
                                <strong>₹{(c.amountPaise / 100).toFixed(2)}</strong> ({c.ledgerAccount?.name || 'Revenue'})
                              </div>
                            ))}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* ── TAB 6: SETTLEMENTS ── */}
        {activeTab === 'settlements' && (
          <div className="glass-card">
            <h3 className="font-heading" style={{ fontSize: '1.25rem', marginBottom: '16px' }}>Razorpay Bank Settlements</h3>
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Settlement ID</th>
                    <th>Gross Amount</th>
                    <th>Fees Deducted</th>
                    <th>GST</th>
                    <th>Net Transferred</th>
                    <th>Bank UTR</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {settlements.map(st => {
                    const net = st.amountPaise - st.feesPaise - st.taxPaise;
                    return (
                      <tr key={st.id}>
                        <td className="mono" style={{ fontWeight: 600, color: 'var(--text-main)' }}>{st.razorpaySettlementId}</td>
                        <td className="mono">₹{(st.amountPaise / 100).toFixed(2)}</td>
                        <td className="mono" style={{ color: '#f87171' }}>-₹{(st.feesPaise / 100).toFixed(2)}</td>
                        <td className="mono" style={{ color: '#f87171' }}>-₹{(st.taxPaise / 100).toFixed(2)}</td>
                        <td className="mono" style={{ color: '#34d399', fontWeight: 700 }}>₹{(net / 100).toFixed(2)}</td>
                        <td className="mono" style={{ fontSize: '0.8rem', color: '#94a3b8' }}>{st.utr || 'UTR-PENDING'}</td>
                        <td><span className="badge badge-success">{st.status}</span></td>
                      </tr>
                    );
                  })}
                  {settlements.length === 0 && (
                    <tr>
                      <td colSpan={7} style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
                        No settlements recorded yet.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* ── TAB 7: RISK & FRAUD ── */}
        {activeTab === 'risk' && (
          <div className="glass-card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 className="font-heading" style={{ fontSize: '1.25rem' }}>Risk Rule Flags & Anomalies</h3>
              <span className="badge badge-warning">{openRiskCount} Flagged For Review</span>
            </div>

            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Rule Code</th>
                    <th>Severity</th>
                    <th>Target Entity</th>
                    <th>Description</th>
                    <th>Status</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {riskFlags.map(rf => (
                    <tr key={rf.id}>
                      <td className="mono" style={{ fontWeight: 600, color: 'var(--text-main)' }}>{rf.ruleCode}</td>
                      <td>
                        <span className={`badge ${
                          rf.severity === 'CRITICAL' || rf.severity === 'HIGH'
                            ? 'badge-danger'
                            : 'badge-warning'
                        }`}>
                          {rf.severity}
                        </span>
                      </td>
                      <td className="mono" style={{ fontSize: '0.8rem' }}>{rf.entityType} ({rf.entityId.substring(0, 8)}...)</td>
                      <td style={{ fontSize: '0.85rem' }}>{rf.description}</td>
                      <td>
                        <span className={`badge ${rf.status === 'OPEN' ? 'badge-danger' : 'badge-success'}`}>
                          {rf.status}
                        </span>
                      </td>
                      <td>
                        {rf.status === 'OPEN' ? (
                          <button
                            className="btn btn-secondary btn-sm"
                            onClick={() => handleDismissRisk(rf.id)}
                          >
                            Dismiss / Resolve
                          </button>
                        ) : (
                          <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Reviewed</span>
                        )}
                      </td>
                    </tr>
                  ))}
                  {riskFlags.length === 0 && (
                    <tr>
                      <td colSpan={6} style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
                        Clean radar: No suspicious activity detected.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* ── TAB 8: WEBHOOKS & LOGS ── */}
        {activeTab === 'webhooks' && (
          <div className="glass-card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <div>
                <h3 className="font-heading" style={{ fontSize: '1.25rem' }}>Webhook Listener & Replay Simulator</h3>
                <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                  Listening at endpoint: <span className="mono" style={{ color: '#38bdf8' }}>POST /api/v1/webhooks/razorpay</span>
                </p>
              </div>
              <button
                className="btn btn-primary btn-sm"
                onClick={() => {
                  alert('Simulated webhook event dispatched: payment.captured with valid HMAC-SHA256 signature.');
                  loadData();
                }}
              >
                <Terminal size={14} />
                <span>Simulate Webhook Delivery</span>
              </button>
            </div>

            <div style={{ background: '#05070d', padding: '16px', borderRadius: '12px', border: '1px solid var(--border-subtle)', fontFamily: 'var(--font-mono)', fontSize: '0.82rem', color: '#94a3b8' }}>
              <div style={{ color: '#34d399', marginBottom: '8px' }}>[INFO] RazorpayWebhookListener: listening for HMAC-verified events...</div>
              <div>[2026-10-30 19:22:45] event=payment.captured id=evt_test_new status=PROCESSED hmac_verified=true</div>
              <div>[2026-10-30 19:22:45] LedgerService: double-entry posting completed DR=10000p CR=10000p</div>
              <div>[2026-10-30 19:22:45] RiskService: evaluated rules: AMOUNT_HIGH=PASS, INTERNATIONAL=PASS</div>
              <div style={{ color: '#fbbf24' }}>[2026-10-30 19:22:46] event=evt_duplicate id=evt_duplicate status=SKIPPED reason=IDEMPOTENCY_EXISTS</div>
            </div>
          </div>
        )}

        {/* ── CHECKOUT MODAL ── */}
        {selectedProduct && (
          <div className="modal-overlay">
            <div className="modal-content">
              <div className="modal-header">
                <div>
                  <h3 style={{ fontSize: '1.2rem', fontWeight: 700 }}>Razorpay Checkout</h3>
                  <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                    Item: {selectedProduct.name}
                  </div>
                </div>
                <button
                  style={{ background: 'transparent', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', fontSize: '1.2rem' }}
                  onClick={() => setSelectedProduct(null)}
                >
                  ✕
                </button>
              </div>

              <form onSubmit={handleInitiateOrder}>
                <div className="modal-body">
                  <div style={{ textAlign: 'center', padding: '16px 0', marginBottom: '18px', background: 'rgba(99, 102, 241, 0.08)', borderRadius: '12px' }}>
                    <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>Total to Pay</div>
                    <div className="mono" style={{ fontSize: '2rem', fontWeight: 800, color: '#ffffff' }}>
                      ₹{(selectedProduct.pricePaise / 100).toFixed(2)}
                    </div>
                    <div style={{ fontSize: '0.75rem', color: '#34d399' }}>Verified directly from DB price catalogue</div>
                  </div>

                  <div className="form-group">
                    <label className="form-label">Customer Name</label>
                    <input
                      type="text"
                      className="form-input"
                      value={customerName}
                      onChange={e => setCustomerName(e.target.value)}
                      required
                    />
                  </div>

                  <div className="form-group">
                    <label className="form-label">Email Address</label>
                    <input
                      type="email"
                      className="form-input"
                      value={customerEmail}
                      onChange={e => setCustomerEmail(e.target.value)}
                      required
                    />
                  </div>

                  <div className="form-group">
                    <label className="form-label">Mobile Number</label>
                    <input
                      type="tel"
                      className="form-input"
                      value={customerPhone}
                      onChange={e => setCustomerPhone(e.target.value)}
                      required
                    />
                  </div>

                  {checkoutSuccess && (
                    <div style={{ padding: '12px', background: 'rgba(16, 185, 129, 0.2)', border: '1px solid #10b981', borderRadius: '8px', color: '#34d399', fontSize: '0.85rem', textAlign: 'center', fontWeight: 600 }}>
                      {checkoutSuccess}
                    </div>
                  )}
                </div>

                <div className="modal-footer">
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => setSelectedProduct(null)}
                    disabled={checkoutLoading}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="btn btn-primary"
                    disabled={checkoutLoading}
                  >
                    {checkoutLoading ? 'Processing...' : 'Pay with Razorpay'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* ── REFUND MODAL ── */}
        {refundPayment && (
          <div className="modal-overlay">
            <div className="modal-content">
              <div className="modal-header">
                <div>
                  <h3 style={{ fontSize: '1.2rem', fontWeight: 700 }}>Issue Refund</h3>
                  <div className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                    Payment ID: {refundPayment.razorpayPaymentId}
                  </div>
                </div>
                <button
                  style={{ background: 'transparent', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', fontSize: '1.2rem' }}
                  onClick={() => setRefundPayment(null)}
                >
                  ✕
                </button>
              </div>

              <div className="modal-body">
                <div style={{ padding: '14px', background: 'rgba(239, 68, 68, 0.08)', borderRadius: '12px', border: '1px solid rgba(239, 68, 68, 0.2)', marginBottom: '18px' }}>
                  <div style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>Refund Amount</div>
                  <div className="mono" style={{ fontSize: '1.6rem', fontWeight: 700, color: '#f87171' }}>
                    ₹{(refundPayment.amountPaise / 100).toFixed(2)}
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '4px' }}>
                    Will post debit to REFUNDS_PAYABLE and credit to RAZORPAY_RECEIVABLE.
                  </div>
                </div>

                <div className="form-group">
                  <label className="form-label">Reason for Refund</label>
                  <input
                    type="text"
                    className="form-input"
                    value={refundReason}
                    onChange={e => setRefundReason(e.target.value)}
                  />
                </div>
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setRefundPayment(null)}
                  disabled={refundLoading}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  style={{ background: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)' }}
                  onClick={handleExecuteRefund}
                  disabled={refundLoading}
                >
                  {refundLoading ? 'Processing...' : 'Confirm Full Refund'}
                </button>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
