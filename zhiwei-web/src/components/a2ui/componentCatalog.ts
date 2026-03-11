import type { Component } from 'vue'
import A2uiText from './A2uiText.vue'
import A2uiCard from './A2uiCard.vue'
import A2uiButton from './A2uiButton.vue'
import A2uiTextField from './A2uiTextField.vue'
import A2uiList from './A2uiList.vue'
import A2uiListItem from './A2uiListItem.vue'
import A2uiDatePicker from './A2uiDatePicker.vue'
import A2uiChip from './A2uiChip.vue'
import A2uiDivider from './A2uiDivider.vue'
import A2uiImage from './A2uiImage.vue'
import A2uiFallback from './A2uiFallback.vue'
import A2uiTable from './A2uiTable.vue'
import A2uiCodeBlock from './A2uiCodeBlock.vue'
import A2uiProgress from './A2uiProgress.vue'

/** A2UI registered component types. Keep this list aligned with the backend catalog contract. */
export const A2UI_COMPONENT_TYPES = [
  'Text',
  'Card',
  'Button',
  'TextField',
  'List',
  'ListItem',
  'DatePicker',
  'Chip',
  'Divider',
  'Image',
  'Table',
  'CodeBlock',
  'Progress',
] as const

export type A2uiRegisteredComponentType = typeof A2UI_COMPONENT_TYPES[number]

/** A2UI component type -> Vue component registry */
const catalog: Record<A2uiRegisteredComponentType, Component> = {
  Text: A2uiText,
  Card: A2uiCard,
  Button: A2uiButton,
  TextField: A2uiTextField,
  List: A2uiList,
  ListItem: A2uiListItem,
  DatePicker: A2uiDatePicker,
  Chip: A2uiChip,
  Divider: A2uiDivider,
  Image: A2uiImage,
  Table: A2uiTable,
  CodeBlock: A2uiCodeBlock,
  Progress: A2uiProgress,
}

/** Resolve a Vue component by type, or fall back when the type is unknown. */
export function resolveComponent(type: string): Component {
  return catalog[type as A2uiRegisteredComponentType] ?? A2uiFallback
}

export default catalog
