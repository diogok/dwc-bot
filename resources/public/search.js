window.onload = function() {
  var form = document.querySelector('#search_form');
  var list = document.querySelector('#list');
  var map = document.querySelector('#map');

  form.onsubmit=function(){
    try {
      search();
    } catch(e) {
      console.log(e);
    }
    return false;
  }

  function search() {
    var q = form.querySelector('input[name=query]').value.trim();
    var start = form.querySelector('input[name=start]').value.trim();
    list.innerHTML = 'Loading...';
    $.getJSON('search?start='+encodeURIComponent(start)+'&q='+encodeURIComponent(q),function(data){
        if(typeof data.error != "undefined"){
          list.innerHTML = JSON.stringify(data);
        } else {
          list.innerHTML = '<p>Found '+data.count+'.</p>';
          for(var i=0;i<data.count;i++){
            display_occurrence(data.result[i]);
          }
        }
    });
  }

  function display_occurrence(occ){
    var div = document.createElement('div');
    div.classList.add('pure-u-1');

    for(var k in occ) {
      var label=document.createElement('strong');
      label.innerHTML=k+': ';
      var value=document.createElement('span');
      value.innerHTML=occ[k];
      var br = document.createElement('br');
      div.appendChild(label);
      div.appendChild(value);
      div.appendChild(br);
    }

    list.appendChild(div);
    var hr = document.createElement('hr');
    list.appendChild(hr);
  }
}
