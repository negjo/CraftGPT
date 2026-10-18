import {isDeepStrictEqual} from 'node:util';

// The JSON Schema subset emitted by CraftGPT's SchemaFactory. Validate before
// inference so an unsupported constraint is never silently ignored.
const keywords=new Set(['type','properties','required','additionalProperties','items','anyOf','enum',
  'minItems','maxItems','minLength','maxLength','pattern','minimum','maximum','description','$schema','title']);
export function checkSchema(schema) {
  if (!schema || typeof schema!=='object' || Array.isArray(schema)) throw new Error('Schema must be an object');
  for (const key of Object.keys(schema)) if (!keywords.has(key)) throw new Error(`Unsupported schema keyword: ${key}`);
  if (schema.type && !['object','array','string','integer','number','boolean','null'].includes(schema.type)) throw new Error('Unsupported schema type');
  if (schema.additionalProperties!==undefined && typeof schema.additionalProperties!=='boolean') throw new Error('Unsupported additionalProperties schema');
  if (schema.pattern) new RegExp(schema.pattern,'u');
  for (const child of Object.values(schema.properties||{})) checkSchema(child);
  if (schema.items) checkSchema(schema.items);
  for (const child of schema.anyOf||[]) checkSchema(child);
}

export function validateOutput(value,schema,at='$') {
  const fail=reason=>{throw new Error(`${at}: ${reason}`);};
  if (schema.anyOf && !schema.anyOf.some(child=>{try {validateOutput(value,child,at);return true;} catch {return false;}})) fail('does not match any allowed shape');
  if (schema.enum && !schema.enum.some(allowed=>isDeepStrictEqual(value,allowed))) fail('value is outside the allowed enum');
  switch(schema.type) {
    case 'object':
      if (!value || typeof value!=='object' || Array.isArray(value)) fail('expected object');
      for (const key of schema.required||[]) if (!Object.hasOwn(value,key)) fail(`missing required field ${key}`);
      for (const [key,item] of Object.entries(value)) {
        if (Object.hasOwn(schema.properties||{},key)) validateOutput(item,schema.properties[key],`${at}.${key}`);
        else if (schema.additionalProperties===false) fail(`unexpected field ${key}`);
      }
      break;
    case 'array':
      if (!Array.isArray(value)) fail('expected array');
      if (schema.minItems!==undefined && value.length<schema.minItems) fail('too few items');
      if (schema.maxItems!==undefined && value.length>schema.maxItems) fail('too many items');
      if (schema.items) value.forEach((item,i)=>validateOutput(item,schema.items,`${at}[${i}]`));
      break;
    case 'string': {
      if (typeof value!=='string') fail('expected string');
      const length=[...value].length;
      if (schema.minLength!==undefined && length<schema.minLength) fail('string too short');
      if (schema.maxLength!==undefined && length>schema.maxLength) fail('string too long');
      if (schema.pattern && !new RegExp(schema.pattern,'u').test(value)) fail('string does not match pattern');
      break;
    }
    case 'integer':
    case 'number':
      if (typeof value!=='number' || !Number.isFinite(value)) fail('expected finite number');
      if (schema.type==='integer' && !Number.isSafeInteger(value)) fail('expected safe integer');
      if (schema.minimum!==undefined && value<schema.minimum) fail('number below minimum');
      if (schema.maximum!==undefined && value>schema.maximum) fail('number above maximum');
      break;
    case 'boolean': if (typeof value!=='boolean') fail('expected boolean'); break;
    case 'null': if (value!==null) fail('expected null'); break;
  }
}

export function structuredTurnInput(input,schema) {
  const hasImages=input.some(item=>item.type==='localImage' || item.type==='image');
  if (!hasImages) return {input,outputSchema:schema};
  return {input:[...input,{type:'text',text:
    'Return exactly one complete JSON object conforming to the following JSON Schema. No markdown, commentary, or tools. '+
    'Keep planning and critique strings concise; the maximum lengths and array sizes are ceilings, not targets. '+
    'The caller validates every field and constraint before using the result.\nJSON Schema:\n'+JSON.stringify(schema)}]};
}