import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';

describe('App shell', () => {
  it('renders workspace route', () => {
    render(
      <MemoryRouter initialEntries={['/workspace']}>
        <App />
      </MemoryRouter>
    );

    expect(screen.getByText(/badgermole workspace/i)).toBeInTheDocument();
  });

  it('renders login route', () => {
    render(
      <MemoryRouter initialEntries={['/login']}>
        <App />
      </MemoryRouter>
    );

    expect(screen.getByText(/badgermole login/i)).toBeInTheDocument();
  });
});
