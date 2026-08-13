import type { CashCategory } from "../api/types";

type Props = {
  categories: CashCategory[];
  value: string | null | undefined;
  onChange: (value: string | null) => void;
  label?: string;
  id?: string;
};

export function CashCategorySelect({ categories, value, onChange, label = "分类", id = "cash-category" }: Props) {
  return <label htmlFor={id}>{label}<select id={id} aria-label={label} value={value ?? ""} onChange={(event) => onChange(event.target.value || null)}><option value="">无分类</option>{categories.map((category) => <option key={category.id} value={category.id}>{category.path.map((item) => item.name).join(" / ")}</option>)}</select></label>;
}
