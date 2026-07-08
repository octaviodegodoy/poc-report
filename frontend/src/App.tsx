import IngestaoPage from "./pages/IngestaoPage";
import logoImg from "./assets/logo.png";

export default function App() {
  return (
    <div className="app-shell">

      {/* ── Top Header ─────────────────────────────── */}
      <header className="app-header">
        <div className="app-header-inner">
          <img src={logoImg} alt="EcoPartners" className="app-header-logo" />
          <span className="app-header-title">Ecossistema de parceiros</span>
        </div>
      </header>

      {/* ── Sidebar + Content ──────────────────────── */}
      <div className="app-body">
        <aside className="sidebar">
          <nav>
            <a className="nav-item active" href="#carga">
              <span className="nav-icon">⬆</span>
              <span className="nav-label">Carga da Base</span>
            </a>
          </nav>
        </aside>
        <main className="content">
          <IngestaoPage />
        </main>
      </div>

    </div>
  );
}
