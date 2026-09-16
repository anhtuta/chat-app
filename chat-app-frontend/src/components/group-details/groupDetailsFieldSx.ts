/** Shared MUI sx so TextFields keep theme text contrast inside the portaled dialog. */
export const groupDetailsTextFieldSx = {
  "& .MuiInputLabel-root": {
    color: "var(--color-text-secondary)",
  },
  "& .MuiInputLabel-root.Mui-focused": {
    color: "var(--color-primary)",
  },
  "& .MuiOutlinedInput-root": {
    backgroundColor: "var(--color-surface-soft)",
    color: "var(--color-text-heading)",
  },
  "& .MuiOutlinedInput-input, & .MuiInputBase-input, & textarea": {
    color: "var(--color-text-heading) !important",
    WebkitTextFillColor: "var(--color-text-heading) !important",
    caretColor: "var(--color-text-heading)",
  },
  "& .MuiOutlinedInput-input::placeholder, & .MuiInputBase-input::placeholder": {
    color: "var(--color-text-secondary)",
    WebkitTextFillColor: "var(--color-text-secondary)",
    opacity: 0.75,
  },
  "& .MuiOutlinedInput-notchedOutline": {
    borderColor: "var(--color-border)",
  },
  "&:hover .MuiOutlinedInput-notchedOutline": {
    borderColor: "var(--color-border-strong)",
  },
  "& .MuiOutlinedInput-root.Mui-focused .MuiOutlinedInput-notchedOutline": {
    borderColor: "var(--color-primary)",
  },
  "& .MuiFormHelperText-root": {
    color: "var(--color-text-secondary)",
  },
  "& .MuiInputBase-input.Mui-disabled, & .MuiOutlinedInput-input.Mui-disabled, & textarea.Mui-disabled": {
    color: "var(--color-text-secondary) !important",
    WebkitTextFillColor: "var(--color-text-secondary) !important",
    opacity: 1,
  },
} as const;
