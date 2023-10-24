// For ISO Material
export const vertexShader = /* glsl */`
in vec3 position;
uniform mat4 modelMatrix;
uniform mat4 modelViewMatrix;
    uniform mat4 projectionMatrix;
    uniform vec3 cameraPos;
    out vec3 vOrigin;
    out vec3 vDirection;
    out vec3 vecToCamera; // Added by me
    void main() {
        vec4 mvPosition = modelViewMatrix * vec4( position, 1.0 );
        vOrigin = vec3( inverse( modelMatrix ) * vec4( cameraPos, 1.0 ) ).xyz;
        vDirection = position - vOrigin;
        vecToCamera = - cameraPos;
        // gl_Position = projectionMatrix * mvPosition;
        gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
    }
`;

export const fragmentShader = /* glsl */`
precision highp float;
precision highp sampler3D;
    uniform mat4 modelViewMatrix;
    uniform mat4 projectionMatrix;
    in vec3 vOrigin;
    in vec3 vDirection;
    in vec3 vecToCamera;
    out vec4 color;
    uniform sampler3D map;
    uniform float threshold;
    uniform float steps;
    uniform float redValue;
    uniform float greenValue;
    uniform float blueValue;
    vec2 hitBox( vec3 orig, vec3 dir ) {
        const vec3 box_min = vec3( - 0.5 );
        const vec3 box_max = vec3( 0.5 );
        vec3 inv_dir = 1.0 / dir;
        vec3 tmin_tmp = ( box_min - orig ) * inv_dir;
        vec3 tmax_tmp = ( box_max - orig ) * inv_dir;
        vec3 tmin = min( tmin_tmp, tmax_tmp );
        vec3 tmax = max( tmin_tmp, tmax_tmp );
        float t0 = max( tmin.x, max( tmin.y, tmin.z ) );
        float t1 = min( tmax.x, min( tmax.y, tmax.z ) );
        return vec2( t0, t1 );
    }
    float sample1( vec3 p ) {
        return texture( map, p ).r;
    }
    #define epsilon .0001
    vec3 normal( vec3 coord ) {
        if ( coord.x < epsilon ) return vec3( 1.0, 0.0, 0.0 );
        if ( coord.y < epsilon ) return vec3( 0.0, 1.0, 0.0 );
        if ( coord.z < epsilon ) return vec3( 0.0, 0.0, 1.0 );
        if ( coord.x > 1.0 - epsilon ) return vec3( - 1.0, 0.0, 0.0 );
        if ( coord.y > 1.0 - epsilon ) return vec3( 0.0, - 1.0, 0.0 );
        if ( coord.z > 1.0 - epsilon ) return vec3( 0.0, 0.0, - 1.0 );
        float step = 0.01;
        float x = sample1( coord + vec3( - step, 0.0, 0.0 ) ) - sample1( coord + vec3( step, 0.0, 0.0 ) );
        float y = sample1( coord + vec3( 0.0, - step, 0.0 ) ) - sample1( coord + vec3( 0.0, step, 0.0 ) );
        float z = sample1( coord + vec3( 0.0, 0.0, - step ) ) - sample1( coord + vec3( 0.0, 0.0, step ) );
        return normalize( vec3( x, y, z ) );
    }
    void main(){
        vec3 myColor = vec3(redValue, greenValue, blueValue);
        vec3 rayDir = normalize( vDirection );
        vec2 bounds = hitBox( vOrigin, rayDir );
        if ( bounds.x > bounds.y ) discard;
        bounds.x = max( bounds.x, 0.0 );
        vec3 p = vOrigin + bounds.x * rayDir;
        vec3 inc = 1.0 / abs( rayDir );
        float delta = min( inc.x, min( inc.y, inc.z ) );
        delta /= steps;
        for ( float t = bounds.x; t < bounds.y; t += delta ) {
            float d = sample1( p + 0.5 );
            if ( d > threshold ) {
                // color.rgb = normal( p + 0.5 ) * 0.5 + ( p * 1.5 + 0.25 );
                
                vec3 dir = normalize(vecToCamera);
                float diffuse = 0. + abs(dot(normal(p + 0.5), dir));
                color.rgb = diffuse * myColor;
                
                color.a = 1.;
                break;
            }
            p += rayDir * delta;
        }
        if ( color.a == 0.0 ) discard;
    }
`;

export const fragmentShader2 = /* glsl */`
precision highp float;
precision highp sampler3D;
    uniform mat4 modelViewMatrix;
    uniform mat4 projectionMatrix;
    in vec3 vOrigin;
    in vec3 vDirection;
    in vec3 vecToCamera;
    out vec4 color;
    uniform sampler3D map1;
    uniform sampler3D map2;
    uniform float threshold;
    uniform float steps;
    uniform float redValue1;
    uniform float greenValue1;
    uniform float blueValue1;
    uniform float redValue2;
    uniform float greenValue2;
    uniform float blueValue2;
    vec2 hitBox( vec3 orig, vec3 dir ) {
        const vec3 box_min = vec3( - 0.5 );
        const vec3 box_max = vec3( 0.5 );
        vec3 inv_dir = 1.0 / dir;
        vec3 tmin_tmp = ( box_min - orig ) * inv_dir;
        vec3 tmax_tmp = ( box_max - orig ) * inv_dir;
        vec3 tmin = min( tmin_tmp, tmax_tmp );
        vec3 tmax = max( tmin_tmp, tmax_tmp );
        float t0 = max( tmin.x, max( tmin.y, tmin.z ) );
        float t1 = min( tmax.x, min( tmax.y, tmax.z ) );
        return vec2( t0, t1 );
    }
    float sample1( vec3 p ) {
        return texture( map1, p ).r;
    }
    float sample2( vec3 p ) {
        return texture( map2, p ).r;
    }
    #define epsilon .0001
    vec3 normal1( vec3 coord ) {
        if ( coord.x < epsilon ) return vec3( 1.0, 0.0, 0.0 );
        if ( coord.y < epsilon ) return vec3( 0.0, 1.0, 0.0 );
        if ( coord.z < epsilon ) return vec3( 0.0, 0.0, 1.0 );
        if ( coord.x > 1.0 - epsilon ) return vec3( - 1.0, 0.0, 0.0 );
        if ( coord.y > 1.0 - epsilon ) return vec3( 0.0, - 1.0, 0.0 );
        if ( coord.z > 1.0 - epsilon ) return vec3( 0.0, 0.0, - 1.0 );
        float step = 0.01;
        float x = sample1( coord + vec3( - step, 0.0, 0.0 ) ) - sample1( coord + vec3( step, 0.0, 0.0 ) );
        float y = sample1( coord + vec3( 0.0, - step, 0.0 ) ) - sample1( coord + vec3( 0.0, step, 0.0 ) );
        float z = sample1( coord + vec3( 0.0, 0.0, - step ) ) - sample1( coord + vec3( 0.0, 0.0, step ) );
        return normalize( vec3( x, y, z ) );
    }
    vec3 normal2( vec3 coord ) {
        if ( coord.x < epsilon ) return vec3( 1.0, 0.0, 0.0 );
        if ( coord.y < epsilon ) return vec3( 0.0, 1.0, 0.0 );
        if ( coord.z < epsilon ) return vec3( 0.0, 0.0, 1.0 );
        if ( coord.x > 1.0 - epsilon ) return vec3( - 1.0, 0.0, 0.0 );
        if ( coord.y > 1.0 - epsilon ) return vec3( 0.0, - 1.0, 0.0 );
        if ( coord.z > 1.0 - epsilon ) return vec3( 0.0, 0.0, - 1.0 );
        float step = 0.01;
        float x = sample2( coord + vec3( - step, 0.0, 0.0 ) ) - sample2( coord + vec3( step, 0.0, 0.0 ) );
        float y = sample2( coord + vec3( 0.0, - step, 0.0 ) ) - sample2( coord + vec3( 0.0, step, 0.0 ) );
        float z = sample2( coord + vec3( 0.0, 0.0, - step ) ) - sample2( coord + vec3( 0.0, 0.0, step ) );
        return normalize( vec3( x, y, z ) );
    }
    void main(){
        vec3 myColor1 = vec3(redValue1, greenValue1, blueValue1);
        vec3 myColor2 = vec3(redValue2, greenValue2, blueValue2);
        vec3 rayDir = normalize( vDirection );
        vec2 bounds = hitBox( vOrigin, rayDir );
        if ( bounds.x > bounds.y ) discard;
        bounds.x = max( bounds.x, 0.0 );
        vec3 p = vOrigin + bounds.x * rayDir;
        vec3 inc = 1.0 / abs( rayDir );
        float delta = min( inc.x, min( inc.y, inc.z ) );
        delta /= steps;
        for ( float t = bounds.x; t < bounds.y; t += delta ) {
            float d1 = sample1( p + 0.5 );
            float d2 = sample2( p + 0.5 );
            if ( d1 > threshold ) {                
                vec3 dir = normalize(vecToCamera);
                float diffuse = 0. + abs(dot(normal1(p + 0.5), dir));
                color.rgb = diffuse * myColor1;
                color.a = 1.;
                break;
            }
            if ( d2 > threshold ) {                
                vec3 dir = normalize(vecToCamera);
                float diffuse = 0. + abs(dot(normal2(p + 0.5), dir));
                color.rgb = diffuse * myColor2;
                color.a = 1.;
                break;
            }
            p += rayDir * delta;
        }
        if ( color.a == 0.0 ) discard;
    }
`;