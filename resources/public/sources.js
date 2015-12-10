window.onload= function(){
  $.getJSON('inputs',function(data){
      for(var i=0;i<data.count;i++){
        display_source(data.result[i]);
      }
  });
  $.getJSON('resources',function(data){
      for(var i=0;i<data.count;i++){
        display_resource(data.result[i]);
      }
  });

  function display_source(url){
    var li = document.createElement('li');
    var a = document.createElement('a');
    a.innerHTML = url;
    a.href=url;
    li.appendChild(a);
    document.querySelector('#sources ul').appendChild(li);
  }

  function display_resource(res){
    var li = document.createElement('li');
    var a = document.createElement('a');
    a.innerHTML = res.title;
    a.href=res.link;
    li.appendChild(a);
    document.querySelector('#resources ul').appendChild(li);
  }
}
