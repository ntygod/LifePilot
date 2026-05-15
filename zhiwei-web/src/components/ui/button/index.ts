import type { VariantProps } from "class-variance-authority"
import { cva } from "class-variance-authority"

export { default as Button } from "./Button.vue"

export const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg border border-transparent text-sm font-medium transition-[color,background-color,border-color,box-shadow,transform] duration-150 active:scale-[0.97] disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4 shrink-0 [&_svg]:shrink-0 outline-none focus-visible:border-ring/70 focus-visible:ring-ring/15 focus-visible:ring-[2px] aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive",
  {
    variants: {
      variant: {
        default:
          "bg-gradient-to-b from-primary to-primary/90 text-primary-foreground shadow-[0_2px_8px_-2px_hsl(from_var(--primary)_h_s_l/0.4),inset_0_1px_0_hsl(from_var(--primary-foreground)_h_s_l/0.12)] hover:from-primary/95 hover:to-primary/85 hover:shadow-[0_4px_14px_-3px_hsl(from_var(--primary)_h_s_l/0.45),inset_0_1px_0_hsl(from_var(--primary-foreground)_h_s_l/0.15)]",
        destructive:
          "bg-gradient-to-b from-destructive to-destructive/90 text-white shadow-[0_2px_8px_-2px_hsl(0_84%_46%/0.3)] hover:from-destructive/95 hover:to-destructive/85 hover:shadow-[0_4px_14px_-3px_hsl(0_84%_46%/0.4)] focus-visible:ring-destructive/20 dark:focus-visible:ring-destructive/40",
        outline:
          "border-border/50 bg-background/90 backdrop-blur-sm shadow-[inset_0_1px_0_rgba(255,255,255,0.5)] hover:bg-accent/50 hover:border-border/70 hover:text-accent-foreground dark:bg-input/35 dark:border-input dark:hover:bg-input/55",
        secondary:
          "bg-secondary/90 text-secondary-foreground backdrop-blur-sm shadow-[inset_0_1px_0_rgba(255,255,255,0.4)] hover:bg-secondary/80",
        ghost:
          "hover:bg-accent/50 hover:text-accent-foreground dark:hover:bg-accent/40",
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
