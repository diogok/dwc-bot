
function dwc() {
  var map = L.map('map').setView([0,0], 1);
  var land = L.tileLayer('http://{s}.tile3.opencyclemap.org/landscape/{z}/{x}/{y}.png')//.addTo(map);
  var ocm = L.tileLayer('http://{s}.tile.opencyclemap.org/cycle/{z}/{x}/{y}.png').addTo(map);
  var osm = L.tileLayer('http://{s}.tile.osm.org/{z}/{x}/{y}.png')//.addTo(map);

  var base = { Landscape: land, OpenCycleMap: ocm, OpenStreetMap: osm };
                      
  L.control.layers(base,{}).addTo(map);
  L.control.scale().addTo(map);

  var markers=null;

  var form = document.querySelector('form');
  var result_div=document.querySelector('.result');
  var prev =document.querySelector('.previous');
  var next =document.querySelector('.next');

  next.onclick=function(){
    var l = document.querySelector('input[name=limit]').value;
    var s = document.querySelector('input[name=start]');
    s.value = parseInt(s.value)+parseInt(l);
    form_submit();
  }

  prev.onclick=function(){
    var l = document.querySelector('input[name=limit]').value;
    var s = document.querySelector('input[name=start]');
    s.value = parseInt(s.value)-parseInt(l);
    form_submit();
  }

  prev.style.display='none';
  next.style.display='none';

  form.onsubmit=form_submit;
  function form_submit() {
    result_div.innerHTML= 'Loading...';
    var q = document.querySelector('input[name=q]').value;
    var s = document.querySelector('input[name=start]').value;
    var l = document.querySelector('input[name=limit]').value;

    if(parseInt(s) >0 ) {
      prev.style.display='inline';
    } else {
      prev.style.display='none';
    }
    reqwest('search?q='+encodeURIComponent(q)+'&start='+s+'&limit='+l,load_result);
    return false;
  }

  function load_result(response) {
    if(response.count == 50) {
      next.style.display='inline';
    } else {
      next.style.display='none';
    }

    result_div.innerHTML='';
    if(markers!=null) {
      map.removeLayer(markers);
    }
    markers = L.markerClusterGroup();
    for(var i=0;i<response.count;i++) {
      var occ = response.result[i];

      var link  = document.createElement('a');
      link.name=occ.occurrenceID;
      result_div.appendChild(link);
      var table = create_table(occ);
      result_div.appendChild(table);
      if(typeof occ.decimalLatitude == 'number' && typeof occ.decimalLongitude == 'number' && occ.decimalLatitude != 0.0 && occ.decimalLongitude != 0.0 ) {
        try {
          var point = L.marker([occ.decimalLatitude,occ.decimalLongitude]);
          markers.addLayer(point);
          (function(occ) {
            table.onclick=function(e) {
              map.setView([occ.decimalLatitude,occ.decimalLongitude]);
            }
            point.on('click',function(e){
              location.hash=occ.occurrenceID;
            })})(occ);
        }catch(e) { }
      }
    }
    map.addLayer(markers);
  }

  function create_table(occ) {
    var table = document.createElement('table');
    table.classList.add('pure-table');


    var tr = document.createElement('tr');
    var th = document.createElement('th');
    th.innerHTML='occurrenceID';
    var td = document.createElement('td');
    td.innerHTML=occ['occurrenceID'];
    tr.appendChild(th);
    tr.appendChild(td);
    table.appendChild(tr);
    for(var k in occ) {
      var tr = document.createElement('tr');
      var th = document.createElement('th');
      th.innerHTML=k;
      var td = document.createElement('td');
      td.innerHTML=occ[k];
      tr.appendChild(th);
      tr.appendChild(td);
      table.appendChild(tr);
    }
    return table;
  }
}

window.onload=dwc;

