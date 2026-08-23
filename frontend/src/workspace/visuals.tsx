import type { ReactNode } from 'react'
import type { ConversationTurn } from '../api/conversations'
import synvoAvatar from '../../assets/logo.jpg'
import defaultUserAvatar from '../../assets/user.png'

export function SynvoLogo({ large = false }: { large?: boolean }) {
  return (
    <img
      className={`workspace-synvo-logo${large ? ' workspace-synvo-logo--large' : ''}`}
      src={synvoAvatar}
      alt=""
      aria-hidden="true"
    />
  )
}

export function ConversationAvatar({
  role,
  userAvatarUrl,
}: {
  role: ConversationTurn['role']
  userAvatarUrl: string | null
}) {
  const userSource = userAvatarUrl?.trim() || defaultUserAvatar
  return (
    <img
      className="workspace-turn__avatar"
      src={role === 'ASSISTANT' ? synvoAvatar : userSource}
      alt=""
      onError={role === 'USER' ? (event) => {
        if (event.currentTarget.getAttribute('src') !== defaultUserAvatar) {
          event.currentTarget.src = defaultUserAvatar
        }
      } : undefined}
    />
  )
}

type IconProps = { className?: string }

function Icon({ children, className }: IconProps & { children: ReactNode }) {
  return <svg className={className} aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{children}</svg>
}

export function PanelLeftIcon() { return <Icon><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></Icon> }
export function PlusIcon() { return <Icon><path d="M12 5v14M5 12h14" /></Icon> }
export function ResearchIcon() { return <Icon><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4M8.5 11h5M11 8.5v5" /></Icon> }
export function MeetingIcon() { return <Icon><rect x="4" y="5" width="16" height="15" rx="2" /><path d="M8 3v4M16 3v4M4 10h16M8 14h3M8 17h6" /></Icon> }
export function ConversationIcon() { return <Icon><path d="M5 5h14v11H9l-4 3V5Z" /></Icon> }
export function TrashIcon() { return <Icon><path d="M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5" /></Icon> }
export function PencilIcon() { return <Icon><path d="m4 20 4.2-1 10.6-10.6a2 2 0 0 0-2.8-2.8L5.4 16.2 4 20Z" /><path d="m14.5 7.1 2.8 2.8" /></Icon> }
export function ArchiveIcon() { return <Icon><path d="M4 7h16v13H4V7Z" /><path d="M3 4h18v3H3V4ZM9 11h6" /></Icon> }
export function PinIcon() { return <Icon><path d="m8 4 8 0-1 5 3 3H6l3-3-1-5ZM12 12v8" /></Icon> }
export function SettingsIcon() { return <Icon><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06-2.83 2.83-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.04 1.56V21h-4v-.08A1.7 1.7 0 0 0 8.96 19.36a1.7 1.7 0 0 0-1.87.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.56-1.04H3v-4h.04A1.7 1.7 0 0 0 4.6 8.92a1.7 1.7 0 0 0-.34-1.87L4.2 7l2.83-2.83.06.06a1.7 1.7 0 0 0 1.87.34A1.7 1.7 0 0 0 10 3.04V3h4v.04a1.7 1.7 0 0 0 1.04 1.56 1.7 1.7 0 0 0 1.87-.34l.06-.06L19.8 7l-.06.05a1.7 1.7 0 0 0-.34 1.87A1.7 1.7 0 0 0 20.96 10H21v4h-.04A1.7 1.7 0 0 0 19.4 15Z" /></Icon> }
export function ArtifactIcon() { return <Icon><path d="M6 3h9l4 4v14H6V3Z" /><path d="M15 3v5h5M9 13h7M9 17h5" /></Icon> }
export function CloseIcon() { return <Icon><path d="m7 7 10 10M17 7 7 17" /></Icon> }
export function ArrowLeftIcon() { return <Icon><path d="m10 6-6 6 6 6M4 12h16" /></Icon> }
export function ArrowUpIcon() { return <Icon><path d="m6 10 6-6 6 6M12 4v16" /></Icon> }
export function StopIcon() { return <Icon><rect x="7" y="7" width="10" height="10" rx="1.5" /></Icon> }
export function CopyIcon() { return <Icon><rect x="8" y="8" width="11" height="11" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" /></Icon> }
export function CheckIcon() { return <Icon><path d="m5 12 4 4L19 6" /></Icon> }
export function BranchIcon() { return <Icon><path d="M6 4v5a3 3 0 0 0 3 3h9M14 8l4 4-4 4M6 20v-3a5 5 0 0 1 5-5" /></Icon> }
export function FolderIcon() { return <Icon><path d="M3 7h7l2 2h9v10H3V7Z" /></Icon> }
export function ShieldIcon() { return <Icon><path d="M12 3 5 6v5c0 4.4 2.8 8 7 10 4.2-2 7-5.6 7-10V6l-7-3Z" /><path d="m9 12 2 2 4-4" /></Icon> }
