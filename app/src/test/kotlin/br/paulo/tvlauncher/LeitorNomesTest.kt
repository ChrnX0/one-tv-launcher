package br.paulo.tvlauncher

import br.paulo.tvlauncher.input.LeitorNomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elevação 8.2: o leitor de nomes com mais de 200 nomes no formato real em que os arquivos chegam (release de cena,
 * fansub de anime, Plex/Kodi, pasta de pendrive em português, programa diário). Cada linha:
 *   arquivo | pastas (separadas por /) | nome esperado | ano | temporada | episódios (separados por ,)
 * Campo vazio = nenhum.
 */
class LeitorNomesTest {

    private val CASOS = """
Dune.Part.Two.2024.1080p.WEB-DL.DDP5.1.Atmos.H.264-FLUX.mkv||Dune Part Two|2024||
Oppenheimer.2023.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1.Atmos-FraMeSToR.mkv||Oppenheimer|2023||
Barbie.2023.720p.WEBRip.x264.AAC-YTS.mp4||Barbie|2023||
The.Shawshank.Redemption.1994.REMASTERED.1080p.BluRay.x265.mkv||The Shawshank Redemption|1994||
Pulp.Fiction.1994.1080p.BrRip.x264.YIFY.mp4||Pulp Fiction|1994||
The Godfather (1972) 1080p BluRay.mkv||The Godfather|1972||
The Godfather Part II (1974).mkv||The Godfather Part II|1974||
Blade Runner 2049 (2017).mkv||Blade Runner 2049|2017||
Blade.Runner.2049.2017.1080p.BluRay.x264.mkv||Blade Runner 2049|2017||
1917.2019.1080p.BluRay.x264.mkv||1917|2019||
1917 (2019).mkv||1917|2019||
2012.2009.720p.BluRay.mkv||2012|2009||
2001.A.Space.Odyssey.1968.1080p.BluRay.mkv||2001 A Space Odyssey|1968||
Wonder.Woman.1984.2020.1080p.WEB-DL.mkv||Wonder Woman 1984|2020||
Interstellar.2014.IMAX.2160p.UHD.BluRay.x265.10bit.HDR.mkv||Interstellar|2014||
Avatar.The.Way.of.Water.2022.1080p.WEBRip.x264.mkv||Avatar The Way of Water|2022||
Spider-Man.Across.the.Spider-Verse.2023.1080p.AMZN.WEB-DL.mkv||Spider-Man Across the Spider-Verse|2023||
Mission.Impossible.Dead.Reckoning.Part.One.2023.1080p.WEB-DL.mkv||Mission Impossible Dead Reckoning Part One|2023||
John.Wick.Chapter.4.2023.1080p.BluRay.x264.mkv||John Wick Chapter 4|2023||
Top.Gun.Maverick.2022.1080p.WEBRip.x265.mkv||Top Gun Maverick|2022||
Everything.Everywhere.All.at.Once.2022.1080p.WEB.H264-NAISU.mkv||Everything Everywhere All at Once|2022||
The.Batman.2022.1080p.HMAX.WEB-DL.DDP5.1.Atmos.H.264.mkv||The Batman|2022||
Parasite.2019.KOREAN.1080p.BluRay.x264.mkv||Parasite|2019||
Am.lie.2001.1080p.BluRay.mkv||Am lie|2001||
Mad.Max.Fury.Road.2015.1080p.BluRay.x264.DTS-HD.MA.7.1.mkv||Mad Max Fury Road|2015||
Inception.2010.1080p.BluRay.x264.DTS.mkv||Inception|2010||
The.Matrix.1999.1080p.BluRay.x264.mkv||The Matrix|1999||
The.Matrix.Reloaded.2003.720p.HDTV.mkv||The Matrix Reloaded|2003||
Alien.1979.Directors.Cut.1080p.BluRay.mkv||Alien|1979||
Alien.Directors.Cut.1979.1080p.mkv||Alien|1979||
Gladiator.2000.EXTENDED.REMASTERED.1080p.BluRay.mkv||Gladiator|2000||
Titanic.1997.720p.BluRay.x264.mkv||Titanic|1997||
Jurassic.Park.1993.1080p.BluRay.x264.mkv||Jurassic Park|1993||
Back.to.the.Future.1985.1080p.BluRay.x264.mkv||Back to the Future|1985||
E.T.the.Extra-Terrestrial.1982.1080p.BluRay.mkv||ET the Extra-Terrestrial|1982||
Star.Wars.Episode.IV.A.New.Hope.1977.1080p.BluRay.mkv||Star Wars Episode IV A New Hope|1977||
Toy.Story.1995.1080p.BluRay.x264.mkv||Toy Story|1995||
Toy Story 4 (2019) [1080p] [BluRay] [5.1] [YTS.MX].mp4||Toy Story 4|2019||
Coco (2017) [1080p].mp4||Coco|2017||
Frozen.II.2019.1080p.WEB-DL.mkv||Frozen II|2019||
Up.2009.1080p.BluRay.x264.mkv||Up|2009||
Her.2013.1080p.BluRay.mkv||Her|2013||
It.2017.1080p.BluRay.x264.mkv||It|2017||
Us.2019.1080p.WEB-DL.mkv||Us|2019||
Joker.2019.1080p.WEBRip.x264.mkv||Joker|2019||
Joker.Folie.a.Deux.2024.1080p.WEB-DL.mkv||Joker Folie a Deux|2024||
Furiosa.A.Mad.Max.Saga.2024.2160p.WEB-DL.DV.HDR.mkv||Furiosa A Mad Max Saga|2024||
Godzilla.Minus.One.2023.JAPANESE.1080p.WEBRip.mkv||Godzilla Minus One|2023||
Poor.Things.2023.1080p.WEB-DL.mkv||Poor Things|2023||
Killers.of.the.Flower.Moon.2023.1080p.ATVP.WEB-DL.mkv||Killers of the Flower Moon|2023||
The.Holdovers.2023.1080p.WEB.h264.mkv||The Holdovers|2023||
Anatomy.of.a.Fall.2023.FRENCH.1080p.WEB.mkv||Anatomy of a Fall|2023||
Past.Lives.2023.1080p.WEB-DL.mkv||Past Lives|2023||
Inside.Out.2.2024.1080p.WEB-DL.mkv||Inside Out 2|2024||
Deadpool.and.Wolverine.2024.1080p.WEB-DL.mkv||Deadpool and Wolverine|2024||
Alien.Romulus.2024.1080p.WEB-DL.mkv||Alien Romulus|2024||
Gladiator.II.2024.1080p.WEB-DL.mkv||Gladiator II|2024||
Wicked.2024.1080p.WEB-DL.mkv||Wicked|2024||
Moana.2.2024.1080p.WEB-DL.mkv||Moana 2|2024||
Mr.Bean's.Holiday.2007.1080p.mkv||Mr Bean's Holiday|2007||
Dr.Strangelove.1964.1080p.BluRay.mkv||Dr Strangelove|1964||
S.W.A.T.2003.1080p.BluRay.mkv||SWAT|2003||
L.A.Confidential.1997.1080p.BluRay.mkv||LA Confidential|1997||
Ocean's.Eleven.2001.1080p.BluRay.mkv||Ocean's Eleven|2001||
Schindler's.List.1993.1080p.BluRay.mkv||Schindler's List|1993||
The_Lord_of_the_Rings_The_Fellowship_of_the_Ring_2001_Extended_1080p.mkv||The Lord of the Rings The Fellowship of the Ring|2001||
the.lord.of.the.rings.the.two.towers.2002.extended.1080p.bluray.mkv||the lord of the rings the two towers|2002||
O.Auto.da.Compadecida.2000.1080p.WEB-DL.mkv||O Auto da Compadecida|2000||
O Auto da Compadecida 2 (2024) Nacional 1080p.mkv||O Auto da Compadecida 2|2024||
Cidade.de.Deus.2002.1080p.BluRay.x264.mkv||Cidade de Deus|2002||
Tropa.de.Elite.2007.1080p.BluRay.Nacional.mkv||Tropa de Elite|2007||
Tropa de Elite 2 - O Inimigo Agora é Outro (2010).mkv||Tropa de Elite 2 - O Inimigo Agora é Outro|2010||
Central.do.Brasil.1998.720p.mkv||Central do Brasil|1998||
Ainda.Estou.Aqui.2024.1080p.WEB-DL.Nacional.mkv||Ainda Estou Aqui|2024||
Bacurau.2019.1080p.WEB-DL.mkv||Bacurau|2019||
Que Horas Ela Volta (2015).mp4||Que Horas Ela Volta|2015||
Minha.Mae.e.uma.Peca.3.2019.Nacional.1080p.mkv||Minha Mae e uma Peca 3|2019||
Divertida Mente 2 (2024) Dublado 1080p.mp4||Divertida Mente 2|2024||
Vingadores.Ultimato.2019.1080p.Dublado.mkv||Vingadores Ultimato|2019||
Homem-Aranha.Sem.Volta.para.Casa.2021.1080p.DUAL.mkv||Homem-Aranha Sem Volta para Casa|2021||
O.Poderoso.Chefao.1972.1080p.Dual.Audio.mkv||O Poderoso Chefao|1972||
Duna.Parte.Dois.2024.1080p.WEB-DL.DUAL.5.1.mkv||Duna Parte Dois|2024||
O Rei Leão 2019 Dublado.mp4||O Rei Leão|2019||
A.Origem.2010.BDRip.1080p.Dublado.mkv||A Origem|2010||
Shrek 2 Dublado.avi||Shrek 2|||
Procurando Nemo.avi||Procurando Nemo|||
Toy Story.mp4||Toy Story|||
Amelie.mkv||Amelie|||
Jumanji.Bem-Vindo.a.Selva.2017.720p.mkv||Jumanji Bem-Vindo a Selva|2017||
Le.Fabuleux.Destin.d.Amelie.Poulain.2001.FRENCH.1080p.mkv||Le Fabuleux Destin d Amelie Poulain|2001||
Das.Boot.1981.Directors.Cut.GERMAN.1080p.BluRay.mkv||Das Boot|1981||
La.Casa.de.Papel.S01E01.1080p.NF.WEB-DL.mkv||La Casa de Papel||1|1
Breaking.Bad.S01E01.720p.BluRay.x264.mkv||Breaking Bad||1|1
Breaking.Bad.S02E05.720p.HDTV.x264.mkv||Breaking Bad||2|5
Breaking.Bad.S05E16.Felina.1080p.BluRay.mkv||Breaking Bad||5|16
Better.Call.Saul.S06E13.Saul.Gone.1080p.AMC.WEB-DL.mkv||Better Call Saul||6|13
Game.of.Thrones.S08E06.The.Iron.Throne.1080p.AMZN.WEB-DL.mkv||Game of Thrones||8|6
game.of.thrones.s01e01.720p.hdtv.x264.mkv||game of thrones||1|1
The.Office.US.S02E01.The.Dundies.720p.mkv||The Office US||2|1
The Office (US) - S03E10 - A Benihana Christmas.mkv||The Office US||3|10
Friends.S01E01.The.One.Where.Monica.Gets.a.Roommate.mkv||Friends||1|1
Friends - 1x01 - The One Where Monica Gets A Roommate.avi||Friends||1|1
Friends.10x17.The.Last.One.avi||Friends||10|17
Seinfeld.S04E11.The.Contest.DVDRip.mkv||Seinfeld||4|11
The.Simpsons.S35E01.720p.WEB.h264.mkv||The Simpsons||35|1
Stranger.Things.S04E09.Chapter.Nine.The.Piggyback.2160p.NF.WEB-DL.mkv||Stranger Things||4|9
Dark.S01E01.Secrets.1080p.NF.WEB-DL.mkv||Dark||1|1
The.Crown.S06E10.1080p.WEB.h264.mkv||The Crown||6|10
The.Last.of.Us.S01E03.Long.Long.Time.1080p.HMAX.WEB-DL.mkv||The Last of Us||1|3
House.of.the.Dragon.S02E08.1080p.WEB.H264.mkv||House of the Dragon||2|8
The.Bear.S03E01.1080p.WEB.h264.mkv||The Bear||3|1
Severance.S02E10.1080p.ATVP.WEB-DL.mkv||Severance||2|10
Shogun.2024.S01E01.Anjin.1080p.DSNP.WEB-DL.mkv||Shogun|2024|1|1
Doctor.Who.2005.S01E01.Rose.720p.mkv||Doctor Who|2005|1|1
Doctor Who (2023) - S01E01 - Space Babies.mkv||Doctor Who|2023|1|1
Battlestar.Galactica.2003.S01E01.mkv||Battlestar Galactica|2003|1|1
Its.Always.Sunny.in.Philadelphia.S16E01.1080p.mkv||Its Always Sunny in Philadelphia||16|1
Grey's.Anatomy.S20E01.720p.HDTV.x264.mkv||Grey's Anatomy||20|1
Marvels.Agents.of.S.H.I.E.L.D.S01E01.720p.mkv||Marvels Agents of SHIELD||1|1
S.W.A.T.2017.S07E01.720p.HDTV.x264.mkv||SWAT|2017|7|1
9-1-1.S07E01.720p.HDTV.x264.mkv||9-1-1||7|1
24.S01E01.12.00.A.M.-1.00.A.M.DVDRip.mkv||24||1|1
The.100.S01E01.Pilot.720p.mkv||The 100||1|1
This.Is.Us.S06E18.mkv||This Is Us||6|18
Lost.S01E01E02.Pilot.720p.BluRay.mkv||Lost||1|1,2
Lost.S06E17-E18.The.End.720p.mkv||Lost||6|17,18
Twin.Peaks.S03E01-E02.1080p.mkv||Twin Peaks||3|1,2
The.Expanse.S01E01-03.1080p.mkv||The Expanse||1|1,2,3
Sherlock.S04E03.The.Final.Problem.1080p.BluRay.mkv||Sherlock||4|3
Black.Mirror.S06E01.Joan.Is.Awful.1080p.NF.WEB-DL.mkv||Black Mirror||6|1
Fleabag.S02E06.720p.mkv||Fleabag||2|6
Chernobyl.S01E05.Vichnaya.Pamyat.1080p.mkv||Chernobyl||1|5
The.Sopranos.S06E21.Made.in.America.mkv||The Sopranos||6|21
The.Wire.S05E10.-30-.mkv||The Wire||5|10
Mad.Men.S07E14.Person.to.Person.mkv||Mad Men||7|14
Succession.S04E10.With.Open.Eyes.1080p.mkv||Succession||4|10
Ted.Lasso.S03E12.1080p.ATVP.WEB-DL.mkv||Ted Lasso||3|12
Only.Murders.in.the.Building.S04E10.1080p.mkv||Only Murders in the Building||4|10
Wandinha.S01E01.1080p.NF.WEB-DL.DUAL.mkv||Wandinha||1|1
Round.6.S02E01.1080p.NF.WEB-DL.Dublado.mkv||Round 6||2|1
Sintonia.S05E01.1080p.NF.WEB-DL.Nacional.mkv||Sintonia||5|1
Cidade.Invisivel.S02E05.1080p.mkv||Cidade Invisivel||2|5
Bom.Dia.Veronica.S03E06.mkv||Bom Dia Veronica||3|6
3.Porcento.S04E07.1080p.mkv||3 Porcento||4|7
Sessao.de.Terapia.T05E01.mkv||Sessao de Terapia||5|1
Os Normais T01E05.avi||Os Normais||1|5
A.Grande.Familia.T14E03.avi||A Grande Familia||14|3
Chaves T1EP05.avi||Chaves||1|5
Chaves - Temporada 1 Episodio 5.mp4||Chaves||1|5
Castelo Rá-Tim-Bum - Episódio 12.mp4||Castelo Rá-Tim-Bum|||12
Pantanal Capitulo 45.mp4||Pantanal|||45
Avenida.Brasil.Cap.179.mp4||Avenida Brasil|||179
Mundo.Bita.Ep.03.mp4||Mundo Bita|||3
Peppa Pig - Season 2 Episode 10.mp4||Peppa Pig||2|10
Bluey.S03E25.The.Sign.1080p.DSNP.WEB-DL.mkv||Bluey||3|25
Bluey (2018) - S01E01 - Magic Xylophone.mkv||Bluey|2018|1|1
Paw.Patrol.S10E05.720p.mkv||Paw Patrol||10|5
The.Mandalorian.S03E08.2160p.DSNP.WEB-DL.mkv||The Mandalorian||3|8
Andor.S02E12.1080p.mkv||Andor||2|12
Loki.S02E06.1080p.mkv||Loki||2|6
WandaVision.S01E09.1080p.mkv||WandaVision||1|9
The.Boys.S04E08.1080p.AMZN.WEB-DL.mkv||The Boys||4|8
Fallout.S01E01.1080p.AMZN.WEB-DL.mkv||Fallout||1|1
The.Rings.of.Power.S02E08.1080p.mkv||The Rings of Power||2|8
Arcane.S02E09.1080p.NF.WEB-DL.mkv||Arcane||2|9
Sherlock.Holmes.2009.1080p.BluRay.mkv||Sherlock Holmes|2009||
Sherlock Season 2.mkv||Sherlock||2|
The.Wire.Season.1.Complete.720p.mkv||The Wire||1|
Friends.S01.Complete.720p.BluRay.mkv||Friends||1|
Dark.Temporada.2.Completa.1080p.mkv||Dark||2|
[SubsPlease] Frieren - 07 (1080p) [ABCD1234].mkv||Frieren|||7
[SubsPlease] Sousou no Frieren - 28 (1080p) [5F2C1A0B].mkv||Sousou no Frieren|||28
[Erai-raws] One Piece - 1071 [1080p][Multiple Subtitle].mkv||One Piece|||1071
[HorribleSubs] Shingeki no Kyojin - 59 [720p].mkv||Shingeki no Kyojin|||59
[Judas] Jujutsu Kaisen - S02E01 [1080p][HEVC x265 10bit].mkv||Jujutsu Kaisen||2|1
[ASW] Dandadan - 12 [1080p HEVC][AB12CD34].mkv||Dandadan|||12
[SubsPlease] Kusuriya no Hitorigoto - 24v2 (1080p) [ABCDEF12].mkv||Kusuriya no Hitorigoto|||24
[Commie] Steins;Gate - 01 [BD 720p AAC] [12345678].mkv||Steins;Gate|||1
[Anime Time] Naruto Shippuden - 500 [1080p].mkv||Naruto Shippuden|||500
Spy x Family - 05 [1080p].mkv||Spy x Family|||5
Cowboy Bebop - 01 - Asteroid Blues.mkv||Cowboy Bebop|||1
Death.Note.E01.Rebirth.720p.mkv||Death Note|||1
Pokemon.S01E01.Pokemon.I.Choose.You.mkv||Pokemon||1|1
The.Daily.Show.2024.05.12.Guest.Name.720p.WEB.h264.mkv||The Daily Show|2024||
Jornal.Nacional.2024.11.15.mp4||Jornal Nacional|2024||
Last.Week.Tonight.with.John.Oliver.2024.03.10.1080p.mkv||Last Week Tonight with John Oliver|2024||
The.Tonight.Show.Starring.Jimmy.Fallon.2023.12.01.720p.mkv||The Tonight Show Starring Jimmy Fallon|2023||
Planet.Earth.II.2016.S01E01.Islands.2160p.BluRay.mkv||Planet Earth II|2016|1|1
Cosmos.A.Spacetime.Odyssey.S01E01.720p.mkv||Cosmos A Spacetime Odyssey||1|1
Our.Planet.S01E01.1080p.NF.WEB-DL.mkv||Our Planet||1|1
Free.Solo.2018.1080p.BluRay.mkv||Free Solo|2018||
The.Social.Dilemma.2020.1080p.NF.WEB-DL.mkv||The Social Dilemma|2020||
01.mkv|Séries/Dark/Temporada 1|Dark||1|1
03.mkv|Séries/Dark/Temporada 2|Dark||2|3
05 - Piloto.mkv|Series/Os Normais/Season 1|Os Normais||1|5
Episode 07.mkv|TV Shows/Fargo/Season 3|Fargo||3|7
S01E02.mkv|TV/The Office (US)/Season 1|The Office US||1|2
Fargo.S02E03.720p.mkv|TV Shows/Fargo/Season 2|Fargo||2|3
Pilot.mkv|Séries/Lost/Season 01|Lost||1|
E05.mkv|Séries/Chaves/T1|Chaves||1|5
Ep 12.mp4|Desenhos/Pica-Pau/Temporada 3|Pica-Pau||3|12
movie.mkv|Filmes/Interstellar (2014)|Interstellar|2014||
VIDEO_TS.mkv|Filmes/Titanic 1997|Titanic|1997||
Interstellar.mkv|Filmes/Interstellar (2014)|Interstellar|||
Dune (2021).mkv|Movies/Dune (2021)|Dune|2021||
Breaking Bad - S01E01 - Pilot.mkv|TV Shows/Breaking Bad (2008)/Season 1|Breaking Bad||1|1
10.mkv|Novelas/Pantanal (2022)/Temporada 1|Pantanal|2022|1|10
02.mp4|Desenhos/Turma da Mônica/Season 2|Turma da Mônica||2|2
12.mp4|Desenhos/Turma da Mônica/Especiais|Turma da Mônica||0|12
01.mkv|Séries/Friends/Specials|Friends||0|1
Dark.S01E01.mkv|Downloads|Dark||1|1
The.Matrix.1999.1080p.mkv|Downloads/Torrents|The Matrix|1999||
Sicario.2015.1080p.BluRay.x264-SPARKS.mkv||Sicario|2015||
Arrival.2016.1080p.BluRay.x264-SPARKS.mkv||Arrival|2016||
Prisoners.2013.1080p.BluRay.x264-SPARKS.mkv||Prisoners|2013||
Drive.2011.1080p.BluRay.x264-SPARKS.mkv||Drive|2011||
Heat.1995.REMASTERED.1080p.BluRay.x264-AMIABLE.mkv||Heat|1995||
Se7en.1995.1080p.BluRay.x264.mkv||Se7en|1995||
Fight.Club.1999.10th.Anniversary.Edition.1080p.BluRay.mkv||Fight Club|1999||
The.Dark.Knight.2008.IMAX.1080p.BluRay.mkv||The Dark Knight|2008||
The.Prestige.2006.1080p.BluRay.mkv||The Prestige|2006||
Memento.2000.1080p.BluRay.mkv||Memento|2000||
Tenet.2020.IMAX.2160p.UHD.BluRay.mkv||Tenet|2020||
Dunkirk.2017.1080p.BluRay.mkv||Dunkirk|2017||
No.Country.for.Old.Men.2007.1080p.BluRay.mkv||No Country for Old Men|2007||
There.Will.Be.Blood.2007.1080p.BluRay.mkv||There Will Be Blood|2007||
Whiplash.2014.1080p.BluRay.mkv||Whiplash|2014||
La.La.Land.2016.1080p.BluRay.mkv||La La Land|2016||
The.Grand.Budapest.Hotel.2014.1080p.BluRay.mkv||The Grand Budapest Hotel|2014||
Spirited.Away.2001.JAPANESE.1080p.BluRay.mkv||Spirited Away|2001||
My.Neighbor.Totoro.1988.1080p.BluRay.mkv||My Neighbor Totoro|1988||
Your.Name.2016.1080p.BluRay.mkv||Your Name|2016||
Akira.1988.1080p.BluRay.mkv||Akira|1988||
Ghost.in.the.Shell.1995.1080p.BluRay.mkv||Ghost in the Shell|1995||
Perfect.Days.2023.1080p.WEB-DL.mkv||Perfect Days|2023||
The.Zone.of.Interest.2023.1080p.WEB-DL.mkv||The Zone of Interest|2023||
Anora.2024.1080p.WEB-DL.mkv||Anora|2024||
The.Brutalist.2024.1080p.WEB-DL.mkv||The Brutalist|2024||
Conclave.2024.1080p.WEB-DL.mkv||Conclave|2024||
The.Substance.2024.1080p.WEB-DL.mkv||The Substance|2024||
Civil.War.2024.1080p.WEB-DL.mkv||Civil War|2024||
Challengers.2024.1080p.WEB-DL.mkv||Challengers|2024||
Twisters.2024.1080p.WEB-DL.mkv||Twisters|2024||
Beetlejuice.Beetlejuice.2024.1080p.WEB-DL.mkv||Beetlejuice Beetlejuice|2024||
A.Quiet.Place.Day.One.2024.1080p.WEB-DL.mkv||A Quiet Place Day One|2024||
Kingdom.of.the.Planet.of.the.Apes.2024.1080p.WEB-DL.mkv||Kingdom of the Planet of the Apes|2024||
The.Wild.Robot.2024.1080p.WEB-DL.mkv||The Wild Robot|2024||
Flow.2024.1080p.WEB-DL.mkv||Flow|2024||
Nosferatu.2024.1080p.WEB-DL.mkv||Nosferatu|2024||
Nosferatu.1922.1080p.BluRay.mkv||Nosferatu|1922||
Metropolis.1927.1080p.BluRay.mkv||Metropolis|1927||
Casablanca.1942.1080p.BluRay.mkv||Casablanca|1942||
Citizen.Kane.1941.1080p.BluRay.mkv||Citizen Kane|1941||
Psycho.1960.1080p.BluRay.mkv||Psycho|1960||
Vertigo.1958.1080p.BluRay.mkv||Vertigo|1958||
Sunset.Blvd.1950.1080p.BluRay.mkv||Sunset Blvd|1950||
Singin.in.the.Rain.1952.1080p.BluRay.mkv||Singin in the Rain|1952||
Seven.Samurai.1954.1080p.BluRay.mkv||Seven Samurai|1954||
Rashomon.1950.1080p.BluRay.mkv||Rashomon|1950||
8.Mile.2002.1080p.BluRay.mkv||8 Mile|2002||
12.Angry.Men.1957.1080p.BluRay.mkv||12 Angry Men|1957||
300.2006.1080p.BluRay.mkv||300|2006||
21.Jump.Street.2012.1080p.BluRay.mkv||21 Jump Street|2012||
10.Things.I.Hate.About.You.1999.1080p.mkv||10 Things I Hate About You|1999||
Apollo.13.1995.1080p.BluRay.mkv||Apollo 13|1995||
District.9.2009.1080p.BluRay.mkv||District 9|2009||
Fantastic.4.2015.1080p.BluRay.mkv||Fantastic 4|2015||
Ocean's.Twelve.2004.720p.mkv||Ocean's Twelve|2004||
Love.Actually.2003.720p.mkv||Love Actually|2003||
Home.Alone.1990.1080p.BluRay.mkv||Home Alone|1990||
Home.Alone.2.Lost.in.New.York.1992.1080p.mkv||Home Alone 2 Lost in New York|1992||
Die.Hard.1988.1080p.BluRay.mkv||Die Hard|1988||
Die.Hard.with.a.Vengeance.1995.1080p.mkv||Die Hard with a Vengeance|1995||
Terminator.2.Judgment.Day.1991.1080p.BluRay.mkv||Terminator 2 Judgment Day|1991||
The.Terminator.1984.1080p.BluRay.mkv||The Terminator|1984||
Rocky.IV.1985.1080p.BluRay.mkv||Rocky IV|1985||
Rambo.First.Blood.Part.II.1985.1080p.mkv||Rambo First Blood Part II|1985||
Indiana.Jones.and.the.Last.Crusade.1989.1080p.mkv||Indiana Jones and the Last Crusade|1989||
Ghostbusters.1984.1080p.BluRay.mkv||Ghostbusters|1984||
Gremlins.1984.1080p.BluRay.mkv||Gremlins|1984||
The.Goonies.1985.1080p.BluRay.mkv||The Goonies|1985||
Ferris.Buellers.Day.Off.1986.1080p.mkv||Ferris Buellers Day Off|1986||
Dirty.Dancing.1987.1080p.mkv||Dirty Dancing|1987||
Pretty.Woman.1990.1080p.mkv||Pretty Woman|1990||
Forrest.Gump.1994.1080p.BluRay.mkv||Forrest Gump|1994||
The.Lion.King.1994.1080p.BluRay.mkv||The Lion King|1994||
Aladdin.1992.1080p.BluRay.mkv||Aladdin|1992||
Aladdin.2019.1080p.BluRay.mkv||Aladdin|2019||
Beauty.and.the.Beast.1991.1080p.mkv||Beauty and the Beast|1991||
Finding.Nemo.2003.1080p.BluRay.mkv||Finding Nemo|2003||
WALL-E.2008.1080p.BluRay.mkv||WALL-E|2008||
Ratatouille.2007.1080p.BluRay.mkv||Ratatouille|2007||
The.Incredibles.2004.1080p.BluRay.mkv||The Incredibles|2004||
Monsters.Inc.2001.1080p.BluRay.mkv||Monsters Inc|2001||
Shrek.2001.1080p.BluRay.mkv||Shrek|2001||
How.to.Train.Your.Dragon.2010.1080p.mkv||How to Train Your Dragon|2010||
Kung.Fu.Panda.2008.1080p.mkv||Kung Fu Panda|2008||
Despicable.Me.2010.1080p.mkv||Despicable Me|2010||
Minions.The.Rise.of.Gru.2022.1080p.mkv||Minions The Rise of Gru|2022||
The.Super.Mario.Bros.Movie.2023.1080p.WEB-DL.mkv||The Super Mario Bros Movie|2023||
Elemental.2023.1080p.WEB-DL.mkv||Elemental|2023||
Encanto.2021.1080p.WEB-DL.mkv||Encanto|2021||
Soul.2020.1080p.DSNP.WEB-DL.mkv||Soul|2020||
Luca.2021.1080p.DSNP.WEB-DL.mkv||Luca|2021||
Turning.Red.2022.1080p.DSNP.WEB-DL.mkv||Turning Red|2022||
""".trimIndent()

    private data class Caso(val arquivo: String, val pastas: List<String>, val nome: String, val ano: Int?, val temporada: Int?, val eps: List<Int>)

    private fun casos(): List<Caso> = CASOS.lines().filter { it.isNotBlank() }.map { l ->
        val c = l.split('|')
        require(c.size == 6) { "linha mal formada: $l" }
        Caso(c[0], c[1].split('/').filter { it.isNotEmpty() }, c[2], c[3].toIntOrNull(), c[4].toIntOrNull(),
            c[5].split(',').filter { it.isNotEmpty() }.map { it.toInt() })
    }

    @Test fun maisDe200NomesReais() {
        val todos = casos()
        assertTrue("só ${todos.size} nomes", todos.size >= 200)
        val erros = todos.mapNotNull { c ->
            val l = LeitorNomes.ler(c.arquivo, c.pastas)
            val obtido = Caso(c.arquivo, c.pastas, l.nome, l.ano, l.temporada, l.episodios)
            if (obtido == c) null else "${c.arquivo}\n   esperado: ${c.nome}|${c.ano}|${c.temporada}|${c.eps}\n   obtido:   ${l.nome}|${l.ano}|${l.temporada}|${l.episodios}"
        }
        assertTrue("${erros.size} de ${todos.size} errados:\n" + erros.joinToString("\n"), erros.isEmpty())
    }

    @Test fun extrasNaoEntramNoCatalogo() {
        assertTrue(LeitorNomes.ler("Dune.Part.Two.2024.1080p.Sample.mkv").extra)
        assertTrue(LeitorNomes.ler("Interstellar.2014.Trailer.mp4").extra)
        assertTrue(LeitorNomes.ler("making.of.mkv", listOf("Filmes", "Interstellar (2014)", "Extras")).extra)
        assertTrue(LeitorNomes.ler("Behind The Scenes.mkv").extra)
        assertEquals(false, LeitorNomes.ler("Dune.Part.Two.2024.1080p.mkv").extra)
    }

    @Test fun serieOuFilme() {
        assertTrue(LeitorNomes.ler("Dark.S01E01.mkv").serie)
        assertTrue(LeitorNomes.ler("Jornal.Nacional.2024.11.15.mp4").serie)
        assertEquals("2024-11-15", LeitorNomes.ler("Jornal.Nacional.2024.11.15.mp4").data)
        assertEquals(false, LeitorNomes.ler("Dune.2021.mkv").serie)
    }

    @Test fun temporadaPelaPasta() {
        assertEquals(2, LeitorNomes.temporadaDaPasta("Season 2"))
        assertEquals(2, LeitorNomes.temporadaDaPasta("Temporada 02"))
        assertEquals(3, LeitorNomes.temporadaDaPasta("S03"))
        assertEquals(1, LeitorNomes.temporadaDaPasta("1ª Temporada"))
        assertEquals(0, LeitorNomes.temporadaDaPasta("Specials"))
        assertEquals(null, LeitorNomes.temporadaDaPasta("Dark"))
    }
}
