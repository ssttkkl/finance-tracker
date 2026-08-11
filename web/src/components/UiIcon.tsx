type IconName = "arrow-left" | "chevron-down" | "eye" | "pencil" | "plus" | "sliders" | "x";

type Props = {
  name: IconName;
  size?: 16 | 20 | 24 | 32;
};

export function UiIcon({ name, size = 20 }: Props) {
  const paths = {
    "arrow-left": <><path d="m12 19-7-7 7-7" /><path d="M19 12H5" /></>,
    "chevron-down": <path d="m6 9 6 6 6-6" />,
    eye: <><path d="M2.06 12.35a1 1 0 0 1 0-.7C3.5 7.58 7.35 5 12 5s8.5 2.58 9.94 6.65a1 1 0 0 1 0 .7C20.5 16.42 16.65 19 12 19s-8.5-2.58-9.94-6.65Z" /><circle cx="12" cy="12" r="3" /></>,
    pencil: <><path d="M12 20h9" /><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" /></>,
    plus: <><path d="M5 12h14" /><path d="M12 5v14" /></>,
    sliders: <><path d="M21 4h-7" /><path d="M10 4H3" /><path d="M21 12h-9" /><path d="M8 12H3" /><path d="M21 20h-5" /><path d="M12 20H3" /><path d="M14 2v4" /><path d="M8 10v4" /><path d="M16 18v4" /></>,
    x: <><path d="M18 6 6 18" /><path d="m6 6 12 12" /></>,
  }[name];

  return <svg className="ui-icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{paths}</svg>;
}
