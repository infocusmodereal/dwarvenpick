import type { ImgHTMLAttributes } from 'react';

type BrandMarkProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'>;

export default function BrandMark({ alt = '', ...props }: BrandMarkProps) {
    return <img {...props} src="/brand/mark-light.png" alt={alt} />;
}
