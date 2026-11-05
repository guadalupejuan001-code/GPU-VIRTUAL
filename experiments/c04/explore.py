#!/usr/bin/env python3
"""Contrasta reglas y consultas contra capturas C03 o vectores CPU declarados."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import time
import zipfile

from relations import Graph, encode
from observe import (make_cases, matrices, to_words, integer_reference,
                     shared_reference, float_reference)
from filters import decode_state, encode_state, words

ROOT=Path(__file__).resolve().parents[2]
sha=lambda blob:hashlib.sha256(blob).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_capture(path):
    cases=[]
    with zipfile.ZipFile(path) as z:
        require(z.testzip() is None,'ZIP corrupto')
        require(len(set(z.namelist()))==len(z.namelist()),'Miembros duplicados')
        inventory_blob=z.read('inventory.json')
        inventory=json.loads(inventory_blob)
        report=json.loads(z.read('report.json'))
        require(report['inventory_sha256']==sha(inventory_blob),'Inventario diferente')
        records={item['key']:item for item in report['cases']}
        require(len(records)==len(report['cases'])==len(inventory['cases']),'Casos repetidos o incompletos')
        for item in inventory['cases']:
            key=item['key'];record=records[key]
            source_blob=z.read(key+'/input.u32');source=to_words(source_blob)
            raw=z.read(key+'/trace.u32');output_blob=z.read(key+'/output.u32')
            require(record['trace_sha256']==sha(raw) and record['output_sha256']==sha(output_blob),key+' hashes')
            programs=[z.read(name) for name in item['programs']]
            require([sha(program) for program in programs]==record['shader_sha256'],key+' shaders')
            if item['kind']=='integer':expected,output=integer_reference(source)
            elif item['kind']=='shared':expected,output=shared_reference(source,item['local_size'])
            elif item['kind']=='float':expected,output=float_reference(source)
            else:raise ValueError('Tipo de caso desconocido')
            phases=matrices(raw,item['n'],item['points'],len(item['names']))
            require(phases==expected and output_blob==words(output),key+' referencia escalar')
            c02_bytes=0
            for point,rows in enumerate(phases):
                blob=z.read(key+'/point-'+str(point)+'.tfs')
                require(decode_state(blob)==(point,item['names'],rows),key+' reconstruccion C02')
                c02_bytes+=len(blob)
            require((record['raw_capture_bytes'],record['filtered_capture_bytes'],record['checked_words'],record['passed'])==
                    (len(raw),c02_bytes,(len(raw)+len(output_blob))//4,True),key+' contadores')
            cases.append(dict(item,phases=phases,output=output,c02_bytes=c02_bytes,raw_bytes=len(raw)))
    require(sum(case['raw_bytes'] for case in cases)==report['raw_capture_bytes'],'Total crudo')
    require(sum(case['c02_bytes'] for case in cases)==report['filtered_capture_bytes'],'Total filtrado')
    require(sum(case['raw_bytes']//4+len(case['output']) for case in cases)==report['checked_words'],'Total verificado')
    require(sum(len(case['programs']) for case in cases)==report['compute_dispatches'],'Despachos')
    hardware=bool(report.get('hardware_gpu_test_passed') and report.get('hardware_driver_identified')
                  and not report.get('software_renderer_detected',True) and not report.get('emulator_detected',True))
    provenance={'source':'user-provided-C03-archive','source_sha256':sha(path.read_bytes()),
                'source_commit':report['source_commit'],'hardware_capture':hardware,
                'device':report.get('model'),'renderer':report.get('gl_renderer'),
                'driver_evidence_basis':report.get('hardware_evidence_basis'),
                'source_compute_dispatches':report['compute_dispatches'],
                'source_checked_words':report['checked_words']}
    return cases,provenance


def synthetic_cases():
    cases=[]
    for n,group in [(1024,64),(257,16)]:
        for item in make_cases(n,group):
            phases,output=item['reference']
            cases.append(dict(key=str(n)+'-'+item['name'],names=item['names'],phases=phases,n=item['n'],
                              output=output,c02_bytes=sum(len(encode_state(item['names'],phase,p)[0]) for p,phase in enumerate(phases)),
                              raw_bytes=sum(len(row)*4 for phase in phases for row in phase)))
    return cases,{'source':'CPU-generated-reference-vectors','hardware_capture':False}


def run(cases,provenance,destination,source_archive=None):
    destination.mkdir(parents=True,exist_ok=True)
    results=[];all_stats=Counter();all_queries={};total_continuation=0
    inventory={'format':'GPU-VIRTUAL-C04-RFS1-pack-v1','cases':[]}
    packed=destination/'captures-c04.zip'
    started=time.perf_counter()
    with zipfile.ZipFile(packed,'w',zipfile.ZIP_DEFLATED) as z:
        for item in cases:
            key,n,names,phases=item['key'],item['n'],item['names'],item['phases']
            sizes={};stats={};blobs={}
            for mode in ['independent','reuse','relations']:
                graph,mode_stats=encode(names,phases,mode)
                blob=graph.to_bytes()
                require(Graph.from_bytes(blob).states()==phases,key+' '+mode+' expansion')
                sizes[mode]=len(blob)
                blobs[mode]=blob
                if mode=='relations':stats=mode_stats
            graph=Graph.from_bytes(blobs['relations'])
            original_bytes=len(graph.to_bytes())
            for p,phase in enumerate(phases):
                for c,name in enumerate(names):
                    ref=graph.field(p,name)
                    for i in sorted(set([0,n//3,n//2,n-1])):
                        require(graph.at(ref,i,all_queries)==phase[i][c],key+' consulta directa')
            final=phases[-1]
            out_column=names.index('published_output');id_column=names.index('id')
            require([row[out_column] for row in final]==item['output'],key+' salida publicada')
            output_ref=graph.field(len(phases)-1,'published_output')
            id_ref=graph.field(len(phases)-1,'id')
            address_name='address' if 'address' in names else 'id'
            address_column=names.index(address_name)
            address_ref=graph.field(len(phases)-1,address_name)
            # Continuacion propia: tres nodos; no recorre el dominio al construirlos.
            scaled=graph.affine(output_ref,3,5)
            mixed=graph.binary(scaled,id_ref,'xor')
            result=graph.gather(mixed,address_ref)
            continued=Graph.from_bytes(graph.to_bytes())
            for i in range(n):
                at=final[i][address_column]
                expected=((3*item['output'][at]+5)&0xffffffff)^final[at][id_column]
                require(continued.at(result,i,all_queries)==expected,key+' continuacion compacta')
                total_continuation+=1
            require(continued.states()==phases,key+' historia tras continuacion')
            filename=key+'.rfs'
            z.writestr(filename,blobs['relations'])
            z.writestr(key+'-continued.rfs',continued.to_bytes())
            manifest={'key':key,'n':n,'points':len(phases),'names':names,'file':filename,
                      'sha256':sha(blobs['relations']),'continued_file':key+'-continued.rfs',
                      'continued_sha256':sha(continued.to_bytes()),'continuation_root':result}
            inventory['cases'].append(manifest)
            record={'key':key,'raw_bytes':item['raw_bytes'],'c02_bytes':item['c02_bytes'],
                    'same_container_independent_bytes':sizes['independent'],'reuse_only_bytes':sizes['reuse'],
                    'relations_bytes':sizes['relations'],'continuation_descriptor_bytes':len(continued.to_bytes())-original_bytes,
                    'continuation_values_checked':n,'operation_counts':stats,'roundtrip_exact':True}
            results.append(record);all_stats.update(stats)
            print(json.dumps(record),flush=True)
        z.writestr('inventory.json',json.dumps(inventory,indent=2))
    totals={field:sum(item[field] for item in results) for field in
            ['raw_bytes','c02_bytes','same_container_independent_bytes','reuse_only_bytes','relations_bytes','continuation_descriptor_bytes']}
    totals.update({'reduction_vs_raw_percent':100*(1-totals['relations_bytes']/totals['raw_bytes']),
                   'reduction_vs_c02_percent':100*(1-totals['relations_bytes']/totals['c02_bytes']),
                   'ratio_raw_over_relations':totals['raw_bytes']/totals['relations_bytes'],
                   'ratio_c02_over_relations':totals['c02_bytes']/totals['relations_bytes']})
    report={'experiment':'C04-relations-and-compact-continuation','provenance':provenance,'cases':results,
            'cases_checked':len(cases),'trace_words_checked':sum(c['raw_bytes']//4 for c in cases),
            'states_checked':sum(len(c['phases']) for c in cases),'columns_checked':sum(len(c['phases'])*len(c['names']) for c in cases),
            'continuation_values_checked':total_continuation,'query_stats':all_queries,'totals':totals,
            'operation_counts':dict(all_stats),'pack_sha256':sha(packed.read_bytes()),
            'implementation_sha256':{file:sha((ROOT/'experiments/c04'/file).read_bytes())
                                     for file in ['relations.py','explore.py','test_relations.py']},
            'host_analysis_wall_seconds':time.perf_counter()-started,
            'scope':{'new_execution_backend':'host CPU Python','original_capture':provenance['source'],
                     'new_hardware_dispatches':0,'new_core_integration':False,'all_observed_trace_values_preserved':True,
                     'continuation_arithmetic':'uint32 modulo 2^32, including float bit patterns as integers',
                     'continuation':'gather((3*published_output+5) XOR id, address or id)',
                     'continuation_construction_scans_domain':False,
                     'verification_expands_traces_and_enumerates_outputs':True,
                     'whole_device_state':False,'physical_register_trace':False,'performance_speedup_demonstrated':False,
                     'global_minimum_demonstrated':False,'cross_case_references':False,
                     'size_basis':'sum of standalone RFS1 bytes including schema, slots, node operands and all referenced data; ZIP size excluded'}}
    (destination/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    # Archivo de entrega autocontenido, con el origen exacto para repetir la verificacion.
    bundle=destination/'GPU-VIRTUAL-C04-RELACIONES.zip'
    with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
        z.write(packed,'captures-c04.zip');z.write(destination/'report.json','report.json')
        for file in ['relations.py','explore.py','test_relations.py','README.md']:
            z.write(ROOT/'experiments/c04'/file,'experiments/c04/'+file)
        for file in ['filters.py','observe.py','gl_capture.py']:
            z.write(ROOT/'experiments/c02'/file,'experiments/c02/'+file)
        if source_archive:z.write(source_archive,'source-C03.zip')
    print(json.dumps({'totals':totals,'cases_checked':len(cases),'continuation_values_checked':total_continuation,
                      'operation_counts':dict(all_stats),'bundle':str(bundle)}),flush=True)
    return report


def main():
    parser=argparse.ArgumentParser()
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--capture',type=Path)
    source.add_argument('--synthetic',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'evidence/current/c04')
    args=parser.parse_args()
    cases,provenance=load_capture(args.capture) if args.capture else synthetic_cases()
    run(cases,provenance,args.output,args.capture)


if __name__=='__main__':main()
