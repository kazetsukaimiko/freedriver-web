type BrandProps = {
  className?: string
}

export function Brand({ className }: BrandProps) {
  return (
    <span className={className ? `brand ${className}` : 'brand'}>
      <img className="brand-icon" src="/assets/freedriver/logos/freedriver-icon.svg" alt="" />
      <span className="brand-name">Freedriver</span>
    </span>
  )
}
