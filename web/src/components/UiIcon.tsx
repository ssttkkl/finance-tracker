type IconName = "account" | "arrow-left" | "calendar" | "chevron-down" | "eye" | "layers" | "more" | "pencil" | "plus" | "receipt" | "sliders" | "tag" | "x";

type Props = {
  name: IconName;
  size?: 16 | 20 | 24 | 32;
};

export function UiIcon({ name, size = 20 }: Props) {
  const paths = {
    account: <><path d="M4 10h16" /><path d="M5 10v9" /><path d="M19 10v9" /><path d="M3 19h18" /><path d="m4 10 8-6 8 6" /><path d="M8 13h2" /><path d="M14 13h2" /></>,
    "arrow-left": <><path d="m12 19-7-7 7-7" /><path d="M19 12H5" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="1" /><path d="M16 3v4M8 3v4M3 10h18" /></>,
    "chevron-down": <path d="m6 9 6 6 6-6" />,
    eye: <><path d="M2.06 12.35a1 1 0 0 1 0-.7C3.5 7.58 7.35 5 12 5s8.5 2.58 9.94 6.65a1 1 0 0 1 0 .7C20.5 16.42 16.65 19 12 19s-8.5-2.58-9.94-6.65Z" /><circle cx="12" cy="12" r="3" /></>,
    layers: <><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="m3 12 9 5 9-5" /><path d="m3 16 9 5 9-5" /></>,
    more: <><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></>,
    pencil: <><path d="M12 20h9" /><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" /></>,
    plus: <><path d="M5 12h14" /><path d="M12 5v14" /></>,
    receipt: <><path d="M5 3h14v18l-3-2-4 2-4-2-3 2V3Z" /><path d="M8 8h8M8 12h8M8 16h4" /></>,
    sliders: <><path d="M21 4h-7" /><path d="M10 4H3" /><path d="M21 12h-9" /><path d="M8 12H3" /><path d="M21 20h-5" /><path d="M12 20H3" /><path d="M14 2v4" /><path d="M8 10v4" /><path d="M16 18v4" /></>,
    tag: <><path d="M20.59 13.41 13.4 20.6a2 2 0 0 1-2.82 0L3.4 13.4a2 2 0 0 1 0-2.82V4h6.59a2 2 0 0 1 1.41.59l7.19 7.18a2 2 0 0 1 0 2.83Z" /><circle cx="7.5" cy="7.5" r="1" /></>,
    x: <><path d="M18 6 6 18" /><path d="m6 6 12 12" /></>,
  }[name];

  return <svg className="ui-icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{paths}</svg>;
}
