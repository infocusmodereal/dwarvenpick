import type { ImgHTMLAttributes } from 'react';
import { useTheme, type ThemeMode } from '../theme/ThemeContext';

type BrandMarkProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'> & {
    surface?: ThemeMode;
};

export default function BrandMark({ surface, alt = '', ...props }: BrandMarkProps) {
    const { theme } = useTheme();

    return <img {...props} src={`/brand/mark-${surface ?? theme}.png`} alt={alt} />;
}
