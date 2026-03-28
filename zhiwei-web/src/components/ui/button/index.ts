import type { VariantProps } from "class-variance-authority"
import { cva } from "class-variance-authority"

export { default as Button } from "./Button.vue"

export const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg border border-transparent text-sm font-medium transition-[color,background-color,border-color,box-shadow,transform] duration-150 active:scale-[0.98] disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4 shrink-0 [&_svg]:shrink-0 outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground shadow-[0_12px_22px_-16px_hsl(var(--shadow-color)/0.24)] hover:bg-primary/94",
        destructive:
          "bg-destructive text-white shadow-[0_12px_22px_-16px_hsl(0_84%_46%/0.24)] hover:bg-destructive/94 focus-visible:ring-destructive/20 dark:focus-visible:ring-destructive/40 dark:bg-destructive/70",
        outline:
          "border-border/64 bg-background/88 shadow-[inset_0_1px_0_rgba(255,255,255,0.42)] hover:bg-accent/64 hover:text-accent-foreground dark:bg-input/35 dark:border-input dark:hover:bg-input/55",
        secondary:
          "bg-secondary/92 text-secondary-foreground shadow-[inset_0_1px_0_rgba(255,255,255,0.34)] hover:bg-secondary/86",
        ghost:
          "hover:bg-accent/62 hover:text-accent-foreground dark:hover:bg-accent/48",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        "default": "h-9 px-4 py-2 has-[>svg]:px-3",
        "sm": "h-8 rounded-md gap-1.5 px-3 has-[>svg]:px-2.5",
        "lg": "h-10 rounded-md px-6 has-[>svg]:px-4",
        "icon": "size-9",
        "icon-sm": "size-8",
        "icon-lg": "size-10",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
)
export type ButtonVariants = VariantProps<typeof buttonVariants>
