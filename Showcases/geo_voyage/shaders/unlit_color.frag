uniform vec4 color;

void main() {
    gl_FragColor = vec4(color.xyz, 1.0);
}
