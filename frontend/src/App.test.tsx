import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { getServiceStatus } from './api/status'

vi.mock('./api/status', () => ({
  getServiceStatus: vi.fn(),
}))

const getServiceStatusMock = vi.mocked(getServiceStatus)

describe('App', () => {
  beforeEach(() => {
    getServiceStatusMock.mockReset()
  })

  it('shows the backend readiness response', async () => {
    getServiceStatusMock.mockResolvedValue({
      service: 'synvo-backend',
      status: 'ready',
    })

    render(<App />)

    expect(screen.getByText('Connecting to backend')).toBeInTheDocument()
    expect(await screen.findByText('Backend connected')).toBeInTheDocument()
    expect(screen.getByText('synvo-backend · ready')).toBeInTheDocument()
  })

  it('shows an error and can retry', async () => {
    getServiceStatusMock
      .mockRejectedValueOnce(new Error('Network request failed'))
      .mockResolvedValueOnce({ service: 'synvo-backend', status: 'ready' })

    render(<App />)

    expect(await screen.findByText('Backend unavailable')).toBeInTheDocument()
    expect(screen.getByText('Network request failed')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Backend connected')).toBeInTheDocument()
  })
})
